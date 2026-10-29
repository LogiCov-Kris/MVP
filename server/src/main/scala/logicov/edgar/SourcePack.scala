package logicov.edgar

import fabric.io.JsonParser
import fabric.rw.*
import fabric.io.JsonFormatter
import logicov.analysis.AgencyRating
import logicov.deal.{AnalystInputs, ContextDocument, ExecutiveOfficer}
import logicov.facts.CallFacts

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * A deal's inputs on disk: the analyst's judgments (deal.json), the discovery call (call-script.md), and the SEC
 * filings the fetcher downloaded (sec/).
 */
case class SourcePack(dir: Path) {
  lazy val inputs: AnalystInputs = JsonParser(Files.readString(dir.resolve(SourcePack.Inputs))).as[AnalystInputs]
  lazy val call: CallFacts = CallFacts.parse(Files.readString(dir.resolve(SourcePack.CallScript)))
  lazy val manifest: SourceManifest = JsonParser(Files.readString(sec.resolve(SourcePack.Manifest))).as[SourceManifest]
  lazy val companyFacts: CompanyFacts = CompanyFacts.parse(JsonParser(Files.readString(sec.resolve(SourcePack.CompanyFacts))))
  lazy val annual: XbrlInstance = instance(FilingRole.AnnualXbrl, FilingRole.AnnualLabels)
  lazy val interim: XbrlInstance = instance(FilingRole.InterimXbrl, FilingRole.InterimLabels)

  def contextDocuments: List[ContextDocument] = {
    val folder = dir.resolve(SourcePack.Context)
    if (!Files.isDirectory(folder)) Nil else {
      val files = Files.list(folder)
      try {
        files.iterator().asScala.filter(_.getFileName.toString.endsWith(".md")).toList.sortBy(_.toString)
          .map(p => ContextDocument(p.getFileName.toString, Files.readString(p)))
      } finally files.close()
    }
  }

  /** Officers extracted by `OfficerExtractor`, kept in the pack so builds stay offline and repeatable. */
  def officers: List[ExecutiveOfficer] = {
    val file = sec.resolve(SourcePack.Officers)
    if (Files.exists(file)) JsonParser(Files.readString(file)).as[List[ExecutiveOfficer]] else Nil
  }

  def writeOfficers(officers: List[ExecutiveOfficer]): Unit =
    Files.writeString(sec.resolve(SourcePack.Officers), JsonFormatter.Default(officers.json))

  def sec: Path = dir.resolve(SourcePack.Sec)

  def text(filing: PackFiling): String = Files.readString(sec.resolve(filing.file))

  /** A peer's latest 10-K XBRL, for figures the peer reports only under its own concepts. */
  def peerAnnual(ticker: String): Option[XbrlInstance] =
    manifest.all(FilingRole.PeerAnnualXbrl).find(_.file == SourcePack.peerAnnualFile(ticker))
      .map(f => XbrlInstance.parse(f.form, f.accession, Files.readAllBytes(sec.resolve(f.file)), None))

  /** A peer's 10-K document, for the credit ratings it discloses. */
  def peerReport(ticker: String): Option[PackFiling] =
    manifest.all(FilingRole.PeerAnnualReport).find(_.file == SourcePack.peerReportFile(ticker))

  /** Peer ratings from the annual report, or explicitly qualified offering ratings from a retained term sheet. */
  def peerRatings(ticker: String): List[AgencyRating] = {
    val file = sec.resolve(SourcePack.peerRatingsFile(ticker))
    val annualRatings = if (Files.exists(file)) JsonParser(Files.readString(file)).as[List[AgencyRating]] else Nil
    if (annualRatings.nonEmpty) annualRatings
    else manifest.all(FilingRole.PeerRatingsOffering).filter(_.file.startsWith(s"peers/${ticker.toUpperCase}-"))
      .sortBy(_.filed).lastOption.toList.flatMap(filing => OfferingRatings.extract(text(filing), filing))
  }

  def writePeerRatings(ticker: String, ratings: List[AgencyRating]): Unit =
    Files.writeString(sec.resolve(SourcePack.peerRatingsFile(ticker)), JsonFormatter.Default(ratings.json))

  def peerFacts(ticker: String): CompanyFacts = CompanyFacts.parse(JsonParser(Files.readString(sec.resolve(SourcePack.peerFile(ticker)))))

  private def instance(xbrl: FilingRole, labels: FilingRole): XbrlInstance = {
    val filing = manifest.one(xbrl)
    XbrlInstance.parse(filing.form, filing.accession, Files.readAllBytes(sec.resolve(filing.file)),
      Some(Files.readAllBytes(sec.resolve(manifest.one(labels).file))))
  }
}

object SourcePack {
  val Inputs = "deal.json"
  val CallScript = "call-script.md"
  val Sec = "sec"
  val Manifest = "manifest.json"
  val CompanyFacts = "companyfacts.json"
  val Officers = "officers.json"
  val Context = "context"

  def peerFile(ticker: String): String = s"peers/${ticker.toUpperCase}.json"

  def peerAnnualFile(ticker: String): String = s"peers/${ticker.toUpperCase}-annual.xml"

  def peerReportFile(ticker: String): String = s"peers/${ticker.toUpperCase}-10k.htm"

  def peerRatingsFile(ticker: String): String = s"peers/${ticker.toUpperCase}-ratings.json"
}
