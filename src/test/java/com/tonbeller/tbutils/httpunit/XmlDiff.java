package com.tonbeller.tbutils.httpunit;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.StringTokenizer;
import java.util.regex.PatternSyntaxException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.FactoryConfigurationError;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;
import org.xml.sax.SAXException;

/**
 * Compares XML files by structure. Significant are Element nodes (ordered), Attribute nodes
 * (unordered) and Text nodes (optional ignoring whitespace). Everything else is ignored
 * (comments, formatting etc). 
 * 
 * @author av
 */
public class XmlDiff {

  private IDiffListener diffListener = null;

  public interface EqualsComparator {
    boolean equals(Object o1, Object o2);
  }

  public static class DefaultEqualsComparator implements EqualsComparator {
    public boolean equals(Object o1, Object o2) {
      return o1.equals(o2);
    }
  }

  public static class IntegerEqualsComparator implements EqualsComparator {
    public boolean equals(Object o1, Object o2) {
      try {
        String s1 = ((String) o1).trim();
        String s2 = ((String) o2).trim();
        int i1 = Integer.parseInt(s1);
        int i2 = Integer.parseInt(s2);
        return i1 == i2;
      } catch (NumberFormatException e) {
        return o1.equals(o2);
      }
    }
  }

  public static class IntegerLeadingZerosEqualsComparator implements EqualsComparator {

    private String delimiter;

    public IntegerLeadingZerosEqualsComparator(String delimiter) {
      this.delimiter = delimiter;
    }

    public IntegerLeadingZerosEqualsComparator() {
      this("/");
    }

    // xxxx/0000123/0004 is equal to xxxx/123/4 
    // 000123 is equal to 123
    public boolean equals(Object o1, Object o2) {
      try {
        String s1 = ((String) o1).trim();
        String s2 = ((String) o2).trim();
        int i1 = Integer.parseInt(s1);
        int i2 = Integer.parseInt(s2);
        return i1 == i2;
      } catch (NumberFormatException e) {
        // Format with slashes, check each substring
        StringTokenizer st1 = new StringTokenizer(((String) o1).trim(), delimiter);
        StringTokenizer st2 = new StringTokenizer(((String) o2).trim(), delimiter);
        if (st1.countTokens() != st2.countTokens())
          return false;

        XmlDiff.EqualsComparator ec = new XmlDiff.IntegerEqualsComparator();
        while (st1.hasMoreTokens()) {
          if (!ec.equals(st1.nextToken(), st2.nextToken()))
            return false;
        }
        return true;
      }
    }
  }

  public static class RegularExpressionEqualsComparator implements EqualsComparator {
    // Example: cust = ' 12' == cust = 12, perl pattern = s/[ ']+/ /g
    String regex;
    String replacement;

    public RegularExpressionEqualsComparator(String regex, String replacement) {
      this.regex = regex;
      this.replacement = replacement;
    }

    public boolean equals(Object o1, Object o2) {
      try {
        String s1 = ((String) o1).replaceAll(regex, replacement);
        String s2 = ((String) o2).replaceAll(regex, replacement);
        return s1.equals(s2);
      } catch (PatternSyntaxException e) {
        return o1.equals(o2);
      }
    }
  }

  /**
   * Beinhaltet den IntegerLeadingZerosEquals-Comparator und
   * ignoriert zusätzlich Date/Time-Konstrukte
   */
  public static class DateTimeIgnoreIntegerLeadingZerosEqualsComparator implements EqualsComparator {
    private String pattern;
    private String delimiter;

    public DateTimeIgnoreIntegerLeadingZerosEqualsComparator() {
      this("", "/");
    }

    public DateTimeIgnoreIntegerLeadingZerosEqualsComparator(String pattern, String delimiter) {
      if (pattern != null)
        this.pattern = pattern;
      else
        this.pattern = "";

      if (delimiter != null)
        this.delimiter = delimiter;
      else
        this.delimiter = "";
    }

    public boolean equals(Object o1, Object o2) {

      // "15.12.2004 12:34 Demo" equals to "15.12.2005 12:35 Demo"
      String str1 = ((String) o1).trim();
      String str2 = ((String) o2).trim();
      int len = pattern.length();

      if ((str1.length() >= len) && (str2.length() >= len)) {
        // may be a date/time fragment
        try {
          SimpleDateFormat formatter = new SimpleDateFormat(pattern);
          formatter.parse(str1.substring(0, len));
          formatter.parse(str2.substring(0, len));

          // date/time fragment found: ignore this part and check the rest
          str1 = str1.substring(len);
          str2 = str2.substring(len);
          return str1.equals(str2);
        } catch (ParseException pe) {
          // not a valid date/time: try next comparator
        }
      }

      // xxxx/0000123/0004 equals to xxxx/123/4 
      // 000123 equals to 123
      try {
        String s1 = ((String) o1).trim();
        String s2 = ((String) o2).trim();
        int i1 = Integer.parseInt(s1);
        int i2 = Integer.parseInt(s2);
        return i1 == i2;
      } catch (NumberFormatException e) {
        // Format with slashes, check each substring
        StringTokenizer st1 = new StringTokenizer(((String) o1).trim(), delimiter);
        StringTokenizer st2 = new StringTokenizer(((String) o2).trim(), delimiter);
        if (st1.countTokens() != st2.countTokens())
          return false;

        XmlDiff.EqualsComparator ec = new XmlDiff.IntegerEqualsComparator();
        while (st1.hasMoreTokens()) {
          if (!ec.equals(st1.nextToken(), st2.nextToken()))
            return false;
        }
        return true;
      }
    }
  }

  private boolean ignoreWhitespace;
  private String xslName;

  // compares value of Text nodes
  private EqualsComparator textComparator = new DefaultEqualsComparator();
  // compares value of Attr nodes
  private EqualsComparator attrComparator = new DefaultEqualsComparator();

  public XmlDiff(boolean ignoreWhitespace) {
    this.ignoreWhitespace = ignoreWhitespace;
  }

  public XmlDiff(boolean ignoreWhitespace, IDiffListener diffListener) {
    this.ignoreWhitespace = ignoreWhitespace;
    this.diffListener = diffListener;
  }

  static class XmlDiffException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private Throwable cause;

    public XmlDiffException(Throwable cause) {
      super(cause.toString());
      this.cause = cause;
    }

    public Throwable getCause() {
      return cause;
    }
  }

  public boolean equals(File f1, File f2) {
    try {
      return equals(f1.toURL(), f2.toURL());
    } catch (MalformedURLException e) {
      throw new XmlDiffException(e);
    }
  }

  public boolean equals(URL u1, URL u2) {
    try {
      DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
      dbf.setValidating(false);
      DocumentBuilder parser = dbf.newDocumentBuilder();

      Document d1 = parser.parse(u1.openStream());
      Document d2 = parser.parse(u2.openStream());

      if (xslName != null) {
        d1 = (Document) transform(d1, u1.toExternalForm());
        d2 = (Document) transform(d2, u2.toExternalForm());
      }

      boolean b = equals(d1, d2);
      if (!b && diffListener == null) {
        System.out.println("\n\nXML Compare failed");
        System.out.println(u1.toExternalForm());
        print(d1);
        System.out.println(u2.toExternalForm());
        print(d2);
        System.out.println("\n\n");
      }
      return b;

    } catch (FactoryConfigurationError e) {
      throw new XmlDiffException(e);
    } catch (ParserConfigurationException e) {
      throw new XmlDiffException(e);
    } catch (SAXException e) {
      throw new XmlDiffException(e);
    } catch (IOException e) {
      throw new XmlDiffException(e);
    } catch (TransformerException e) {
      throw new XmlDiffException(e);
    }
  }

  private Node transform(Node node, String systemId) throws TransformerException {
    if (xslName == null)
      return node;
    String xsl = getClass().getResource(xslName).toExternalForm();
    TransformerFactory tf = TransformerFactory.newInstance();
    Transformer tr = tf.newTransformer(new StreamSource(xsl));
    DOMSource src = new DOMSource(node);
    src.setSystemId(systemId);
    DOMResult res = new DOMResult();
    tr.transform(src, res);
    return res.getNode();
  }

  public static void print(Node node) throws TransformerException {
    TransformerFactory tf = TransformerFactory.newInstance();
    String xsl = XmlDiff.class.getResource("pretty.xsl").toExternalForm();
    Source src = new DOMSource(node);
    Result dest = new StreamResult(System.out);
    Transformer t = tf.newTransformer(new StreamSource(xsl));
    t.transform(src, dest);
  }

  public boolean equals(Document d1, Document d2) {
    return equals(d1.getDocumentElement(), d2.getDocumentElement());
  }

  public boolean equals(Element e1, Element e2) {
    String n1 = e1.getNodeName();
    String n2 = e2.getNodeName();
    if (!n1.equals(n2)) {
      if (diffListener == null)
        System.out.println("Different elements found " + n1 + " != " + n2);
      else
        diffListener.notifyDiffElements(e1, e2);
      return false;
    }

    // compate attributes
    NamedNodeMap atts1 = e1.getAttributes();
    NamedNodeMap atts2 = e2.getAttributes();
    if (atts1.getLength() != atts2.getLength()) {
      if (diffListener == null)
        System.out.println("Different number of attributes " + n1 + ": " + atts1.getLength()
            + " != " + n2 + ": " + atts2.getLength());
      else
        diffListener.notifyDiffNumberOfAttributes(e1, e2);

      return false;
    }
    final int N = atts1.getLength();
    for (int i = 0; i < N; i++) {
      Attr a1 = (Attr) atts1.item(i);
      Attr a2 = (Attr) atts2.getNamedItem(a1.getName());
      if (a2 == null) {
        if (diffListener == null) {
          System.out.println("Attributes differ: " + n1 + "." + a1.getName() + " not found");
          return false;
        } else {
          if (diffListener.notifyDiffAttributeMissing(a1))
            continue;
          else
            return false;
        }
       
      }
      if (!attrComparator.equals(a1.getValue(), a2.getValue())) {
        if (diffListener == null) {
          System.out.println("Attributes differ: " + n1 + "." + a1.getName() + ": " + a1.getValue()
              + " != " + a2.getValue());
          return false;
        } else {
          if ( !diffListener.notifyDiffAttributes(a1, a2))
            return false;
        }
      }
    }

    // compare child elements
    List childs1 = getChildren(e1);
    List childs2 = getChildren(e2);
    if (ignoreWhitespace) {
      removeEmpty(childs1);
      removeEmpty(childs2);
    }

    if (childs1.size() != childs2.size()) {
      if (diffListener == null)
      System.out.println("Children count of " + n1 + " differ: " + childs1.size() + " != "
          + childs2.size());
      else
        diffListener.notifyDiffNumberOfChildren(e1, e2);
      
      return false;
    }
    Iterator it1 = childs1.iterator();
    Iterator it2 = childs2.iterator();
    for (; it1.hasNext();) {
      Node c1 = (Node) it1.next();
      Node c2 = (Node) it2.next();
      if (!equals(c1, c2))
        return false;
    }
    return true;
  }

  /**
   * removes Text nodes containing whitespace
   */
  private void removeEmpty(List nodes) {
    for (Iterator it = nodes.iterator(); it.hasNext();) {
      Node n = (Node) it.next();
      if (n.getNodeType() != Node.TEXT_NODE)
        continue;
      Text t = (Text) n;
      String s = t.getData();
      if (s.trim().length() == 0)
        it.remove();
    }
  }

  public boolean equals(Text t1, Text t2) {
    String s1 = t1.getData();
    String s2 = t2.getData();
    if (ignoreWhitespace) {
      s1 = s1.trim();
      s2 = s2.trim();
    }
    if (!textComparator.equals(s1, s2)) {
      if (diffListener == null)
        System.out.println("Different text elements: \"" + s1 + "\" != \"" + s2 + "\"");
       else {
         if ( diffListener.notifyDiffText(t1, t2) )
           return true;
       }
      return false;
    }
    return true;
  }

  public boolean equals(Node n1, Node n2) {
    // different types can not be equal
    if (n1.getNodeType() != n2.getNodeType()) {
      if (diffListener == null)
        System.out.println("Differnt node type: " + n1.getNodeType() + " != " + n2.getNodeType());
      else
        diffListener.notifyDiffNodeType(n1, n2);
      return false;
    }

    if (n1.getNodeType() == Node.ELEMENT_NODE)
      return equals((Element) n1, (Element) n2);
    if (n1.getNodeType() == Node.TEXT_NODE)
      return equals((Text) n1, (Text) n2);

    // ignore other node types (comments and such)
    return true;
  }

  private static List getChildren(Node parent) {
    List list = new ArrayList();
    NodeList children = parent.getChildNodes();
    for (int i = 0; i < children.getLength(); ++i) {
      if (children.item(i).getNodeType() == Node.ELEMENT_NODE
          || children.item(i).getNodeType() == Node.TEXT_NODE)
        list.add(children.item(i));
    }
    return list;
  }

  public boolean isIgnoreWhitespace() {
    return ignoreWhitespace;
  }

  public void setIgnoreWhitespace(boolean b) {
    ignoreWhitespace = b;
  }

  /**
   * name eines XSL Stylesheets fuer Class.getResource(). Dieses
   * Stylesheet wird vor dem Vergleich der XML Dateien auf beiden 
   * Instanzen ausgefuehrt, z.B. um Knoten zu Filtern, zu Sortieren usw. 
   */
  public String getXslName() {
    return xslName;
  }

  public void setXslName(String xslName) {
    this.xslName = xslName;
  }

  public EqualsComparator getAttrComparator() {
    return attrComparator;
  }

  public void setAttrComparator(EqualsComparator attrComparator) {
    this.attrComparator = attrComparator;
  }

  public EqualsComparator getTextComparator() {
    return textComparator;
  }

  public void setTextComparator(EqualsComparator textComparator) {
    this.textComparator = textComparator;
  }

  public interface IDiffListener {
    void notifyDiffElements(Element e1, Element e2);
    void notifyDiffNumberOfChildren(Element e1, Element e2);
    void notifyDiffNumberOfAttributes(Element e1, Element e2);
 
    /**
     * @return true, if the difference is to be ignored
     */
     boolean notifyDiffAttributeMissing(Attr a1);
 
    /**
     * @return true, if the difference is to be ignored
     */
    boolean notifyDiffAttributes(Attr a1, Attr a2);

    /**
     * @return true, if the difference is to be ignored
     */
    boolean notifyDiffText(Text t1, Text t2);
    
    void notifyDiffNodeType(Node n1, Node n2);
  } // IDiffListener
}
