package logicov.edgar

import org.w3c.dom.{Element, Node}

import java.io.ByteArrayInputStream
import java.time.LocalDate
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A filing's full XBRL instance: every fact with its dimensions, plus the standard labels from the label linkbase.
 */
case class XbrlInstance(form: String, accession: String, facts: List[InstanceFact], labels: Map[String, String]) {
  lazy val periodEnd: LocalDate = LocalDate.parse(text("DocumentPeriodEndDate"))

  def text(concept: String): String = facts.find(_.concept == concept).map(_.text)
    .getOrElse(throw new NoSuchElementException(s"$form $accession has no $concept"))

  def amount(concept: String, start: Option[LocalDate], end: LocalDate, dimensions: Map[String, String] = Map.empty): Option[BigDecimal] =
    facts.find(f => f.concept == concept && f.start == start && f.end == end && f.dimensions == dimensions && f.isUsd).map(_.amount)

  def label(name: String): String = labels.getOrElse(name, name.stripSuffix("Member"))
}

object XbrlInstance {
  private val Xbrli = "http://www.xbrl.org/2003/instance"
  private val Xbrldi = "http://xbrl.org/2006/xbrldi"
  private val Link = "http://www.xbrl.org/2003/linkbase"
  private val XLink = "http://www.w3.org/1999/xlink"
  private val StandardLabel = "http://www.xbrl.org/2003/role/label"
  private val MaxText = 64

  def parse(form: String, accession: String, instance: Array[Byte], labelLinkbase: Option[Array[Byte]]): XbrlInstance = {
    val root = document(instance)
    val contexts = children(root, Xbrli, "context").map(c => c.getAttribute("id") -> context(c)).toMap
    val units = children(root, Xbrli, "unit").map(u => u.getAttribute("id") -> localName(descendants(u, Xbrli, "measure").head.getTextContent)).toMap
    val facts = elements(root).filter(_.hasAttribute("contextRef")).flatMap { e =>
      val content = e.getTextContent.trim
      val (start, end, dimensions) = contexts(e.getAttribute("contextRef"))
      if (content.isEmpty || content.length > MaxText) None
      else Some(InstanceFact(e.getLocalName, start, end, dimensions, Option(e.getAttribute("unitRef")).filter(_.nonEmpty).map(units), content))
    }.distinct
    XbrlInstance(form, accession, facts, labelLinkbase.fold(Map.empty[String, String])(l => labels(document(l))))
  }

  private def context(c: Element): (Option[LocalDate], LocalDate, Map[String, String]) = {
    def date(name: String) = descendants(c, Xbrli, name).headOption.map(d => LocalDate.parse(d.getTextContent.trim))
    val dimensions = descendants(c, Xbrldi, "explicitMember").map(m => localName(m.getAttribute("dimension")) -> localName(m.getTextContent)).toMap ++
      descendants(c, Xbrldi, "typedMember").map(m => localName(m.getAttribute("dimension")) -> m.getTextContent.trim).toMap
    val end = date("endDate").orElse(date("instant"))
      .getOrElse(throw new IllegalArgumentException(s"XBRL context ${c.getAttribute("id")} has no period"))
    (date("startDate"), end, dimensions)
  }

  private def labels(root: Element): Map[String, String] = {
    val locators = descendants(root, Link, "loc").map(l => l.getAttributeNS(XLink, "label") -> conceptName(l.getAttributeNS(XLink, "href"))).toMap
    val texts = descendants(root, Link, "label").filter(_.getAttributeNS(XLink, "role") == StandardLabel)
      .map(l => l.getAttributeNS(XLink, "label") -> l.getTextContent.trim.stripSuffix(" [Member]")).toMap
    descendants(root, Link, "labelArc").flatMap { arc =>
      for {
        concept <- locators.get(arc.getAttributeNS(XLink, "from"))
        text <- texts.get(arc.getAttributeNS(XLink, "to"))
      } yield concept -> text
    }.toMap
  }

  private def conceptName(href: String): String = href.substring(href.indexOf('#') + 1).dropWhile(_ != '_').drop(1)

  private def localName(qualified: String): String = qualified.trim.substring(qualified.trim.indexOf(':') + 1)

  private def document(bytes: Array[Byte]): Element = {
    val factory = DocumentBuilderFactory.newInstance()
    factory.setNamespaceAware(true)
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    factory.setAttribute("jdk.xml.maxGeneralEntitySizeLimit", "0")
    factory.setAttribute("jdk.xml.totalEntitySizeLimit", "0")
    factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes)).getDocumentElement
  }

  private def elements(parent: Node): List[Element] = {
    val nodes = parent.getChildNodes
    (0 until nodes.getLength).map(nodes.item).collect { case e: Element => e }.toList
  }

  private def children(parent: Element, namespace: String, name: String): List[Element] =
    elements(parent).filter(e => e.getNamespaceURI == namespace && e.getLocalName == name)

  private def descendants(parent: Element, namespace: String, name: String): List[Element] = {
    val nodes = parent.getElementsByTagNameNS(namespace, name)
    (0 until nodes.getLength).map(i => nodes.item(i).asInstanceOf[Element]).toList
  }
}
