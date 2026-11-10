package logicov.tools

import org.w3c.dom.{Document, Element}
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.{OutputKeys, TransformerFactory}
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

object ToolXml {
  def read(path: Path): Document = {
    val factory = DocumentBuilderFactory.newInstance()
    factory.setNamespaceAware(true)
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    factory.newDocumentBuilder().parse(path.toFile)
  }
  def children(element: Element): List[Element] = {
    val nodes = element.getChildNodes
    (0 until nodes.getLength).toList.flatMap(i => nodes.item(i) match { case e: Element => Some(e); case _ => None })
  }
  def descendants(element: Element, name: String): List[Element] = {
    val nodes = element.getElementsByTagNameNS("*", name)
    (0 until nodes.getLength).toList.map(i => nodes.item(i).asInstanceOf[Element])
  }
  def text(element: Element, name: String): String = descendants(element, name).headOption.fold("")(_.getTextContent.trim)
  def local(value: String): String = value.split(':').last
  def bytes(document: Document): Array[Byte] = {
    val output = new ByteArrayOutputStream()
    val factory = TransformerFactory.newInstance()
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "")
    val transformer = factory.newTransformer()
    transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
    transformer.transform(new DOMSource(document), new StreamResult(output))
    output.toByteArray
  }
}
