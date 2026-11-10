package logicov.tools

import fabric.Json
import fabric.rw.*
import org.w3c.dom.Element
import java.nio.file.{Files, Path, StandardCopyOption}
import scala.collection.mutable

object PackTrimmer {
  private val XLink = "http://www.w3.org/1999/xlink"
  private def strings(path: Path): Set[String] = "\"([A-Z][A-Za-z]+)\"".r.findAllMatchIn(Files.readString(path)).map(_.group(1)).toSet
  private def save(path: Path, bytes: Array[Byte]): Unit = { Files.createDirectories(path.getParent); Files.write(path, bytes); () }
  private def instance(input: Path, output: Path, keep: String => Boolean): Set[String] = {
    val document = ToolXml.read(input)
    val root = document.getDocumentElement
    val children = ToolXml.children(root)
    val facts = children.filter(e => e.hasAttribute("contextRef") && keep(e.getLocalName)).toSet
    val contexts = facts.map(_.getAttribute("contextRef"))
    val units = facts.map(_.getAttribute("unitRef"))
    val members = mutable.Set.empty[String]
    children.foreach { element =>
      val retained = if (element.hasAttribute("contextRef")) facts.contains(element) else element.getLocalName match {
        case "context" => contexts.contains(element.getAttribute("id"))
        case "unit" => units.contains(element.getAttribute("id"))
        case "schemaRef" => true
        case _ => false
      }
      if (!retained) root.removeChild(element)
      else if (element.getLocalName == "context") members ++= ToolXml.descendants(element, "explicitMember").map(m => ToolXml.local(m.getTextContent.trim))
    }
    save(output, ToolXml.bytes(document))
    members.toSet
  }
  private def labels(input: Path, output: Path, members: Set[String]): Unit = {
    val document = ToolXml.read(input)
    ToolXml.descendants(document.getDocumentElement, "labelLink").foreach { link =>
      val children = ToolXml.children(link)
      val locs = children.filter(_.getLocalName == "loc").map { e =>
        val fragment = e.getAttributeNS(XLink, "href").split('#').last
        e.getAttributeNS(XLink, "label") -> fragment.drop(fragment.indexOf('_') + 1)
      }.toMap
      val arcs = children.filter(e => e.getLocalName == "labelArc" && locs.get(e.getAttributeNS(XLink, "from")).exists(members.contains)).toSet
      val from = arcs.map(_.getAttributeNS(XLink, "from")); val to = arcs.map(_.getAttributeNS(XLink, "to"))
      children.foreach { e =>
        val keep = e.getLocalName match {
          case "loc" => from.contains(e.getAttributeNS(XLink, "label"))
          case "label" => to.contains(e.getAttributeNS(XLink, "label"))
          case "labelArc" => arcs.contains(e)
          case _ => false
        }
        if (!keep) link.removeChild(e)
      }
    }
    save(output, ToolXml.bytes(document))
  }
  private def companyFacts(input: Path, output: Path, tags: Set[String], since: String): Unit = {
    val data = ToolFiles.read(input)
    val facts = data("facts")("us-gaap").asObj.value.toList.flatMap { (key, concept) =>
      ToolFiles.optional(concept("units"), "USD").filter(_ => tags.contains(key)).map { values =>
        key -> Map("units" -> Map("USD" -> ToolFiles.array(values).filter(_("end").asString >= since).json).json).json
      }
    }.toMap
    ToolFiles.write(output, Map("cik" -> data("cik"), "entityName" -> data("entityName"), "facts" -> Map("us-gaap" -> facts.json).json).json)
  }
  private def text(html: String): String = html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ")
  def run(source: Path, destination: Path, repo: Path): Unit = {
    val input = source.toAbsolutePath.normalize(); val out = destination.toAbsolutePath.normalize()
    require(!input.startsWith(out) && !out.startsWith(input), "Fixture destination must be separate from the source pack")
    require(!Files.exists(out), "Fixture destination must not already exist; inspect the new fixture before replacing the retained one")
    val deal = ToolFiles.read(input.resolve("deal.json"))
    val tags = strings(repo.resolve("core/src/main/scala/logicov/grid/LineItem.scala"))
    val sourceFiles = List("edgar/SegmentExtractor.scala", "edgar/DebtSchedule.scala", "deal/DealBuilder.scala")
    val concepts = sourceFiles.flatMap(p => strings(repo.resolve("server/src/main/scala/logicov/" + p))).toSet ++ Set(
      "NetIncomeLossAvailableToCommonStockholdersBasic", "DocumentPeriodEndDate", "DebtInstrumentInterestRateStatedPercentage",
      "LineOfCreditFacilityMaximumBorrowingCapacity", "LettersOfCreditOutstandingAmount", "LineOfCreditFacilityRemainingBorrowingCapacity", "DebtInstrumentCarryingAmount") ++
      ToolFiles.optional(deal("ebitda"), "spread").toList.flatMap(s => s("base").asString :: ToolFiles.array(s("addBacks")).map(_.asString))
    Files.createDirectories(out.resolve("sec/peers"))
    List("deal.json", "call-script.md").foreach(f => Files.copy(input.resolve(f), out.resolve(f)))
    val sec = input.resolve("sec")
    val manifest = ToolFiles.read(sec.resolve("manifest.json"))
    val filings = ToolFiles.array(manifest("filings")).filterNot(_("role").asString.split('.').last == "PeerAnnualReport")
    ToolFiles.write(out.resolve("sec/manifest.json"), manifest.asObj.value.updated("filings", filings.json).json)
    if (Files.exists(sec.resolve("officers.json"))) Files.copy(sec.resolve("officers.json"), out.resolve("sec/officers.json"))
    val members = mutable.Map.empty[String, Set[String]]
    def paths(f: Json): (Path, Path) = {
      val relative = Path.of(f("file").asString)
      require(!relative.isAbsolute && !relative.normalize().startsWith(".."), "Unsafe source manifest path")
      sec.resolve(relative) -> out.resolve("sec").resolve(relative)
    }
    filings.foreach { f =>
      val (from, to) = paths(f)
      f("role").asString.split('.').last match {
        case role @ ("AnnualXbrl" | "InterimXbrl") => members(role) = instance(from, to, concepts.contains)
        case "PeerAnnualXbrl" => instance(from, to, _.contains("CapitalExpenditures"))
        case _ => ()
      }
    }
    filings.foreach { f =>
      val (from, to) = paths(f)
      f("role").asString.split('.').last match {
        case "AnnualLabels" => labels(from, to, members("AnnualXbrl"))
        case "InterimLabels" => labels(from, to, members("InterimXbrl"))
        case "PeerRatingsOffering" => save(to, Files.readAllBytes(from))
        case "Proxy" =>
          val content = text(Files.readString(from))
          val passages = "Director since [0-9]{4}".r.findAllMatchIn(content).map(m => content.substring((m.start - 100).max(0), (m.end + 2000).min(content.length)))
          save(to, ("<html><body>" + passages.map(p => "<p>" + p + "</p>").mkString + "</body></html>").getBytes("UTF-8"))
        case "AnnualReport" =>
          val passages = "(?i)[^.]{0,400}contract life[^.]{0,300}\\.".r.findAllIn(text(Files.readString(from)))
          save(to, ("<html><body>" + passages.map(p => "<p>" + p.trim + "</p>").mkString + "</body></html>").getBytes("UTF-8"))
        case "EarningsRelease" =>
          val tables = "(?is)<table.*?</table>".r.findAllIn(Files.readString(from)).filter(_.contains("EBITDA"))
          save(to, ("<html><body>" + tables.mkString + "</body></html>").getBytes("UTF-8"))
        case _ => ()
      }
    }
    companyFacts(sec.resolve("companyfacts.json"), out.resolve("sec/companyfacts.json"), tags, "2022-12-31")
    ToolFiles.array(deal("peers")).foreach { peer =>
      val ticker = peer("ticker").asString.toUpperCase
      require(ticker.matches("[A-Z0-9.-]+"), "Invalid peer ticker")
      val ratings = s"peers/$ticker-ratings.json"
      if (Files.exists(sec.resolve(ratings))) Files.copy(sec.resolve(ratings), out.resolve("sec").resolve(ratings))
      companyFacts(sec.resolve(s"peers/$ticker.json"), out.resolve(s"sec/peers/$ticker.json"), tags, "2024-12-31")
    }
    println(s"Trimmed fixture written to $destination; review it before replacing retained fixtures")
  }
}
