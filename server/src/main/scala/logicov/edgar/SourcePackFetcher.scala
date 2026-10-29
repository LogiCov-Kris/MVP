package logicov.edgar

import fabric.Json
import fabric.io.JsonFormatter
import fabric.rw.*

import java.nio.file.{Files, Path}
import java.time.LocalDate

/**
 * Downloads a source pack's SEC filings: the latest 10-K (document, XBRL, labels), the 10-Q after it, the Q4
 * earnings releases for the three fiscal years, and companyfacts for the borrower and each peer.
 */
class SourcePackFetcher(client: EdgarClient) {
  private val ReleaseWindowDays = 75L
  private val ExhibitLink = """href="(/Archives/edgar/data/[^"]+\.htm)"""".r

  def fetch(pack: SourcePack): SourceManifest = {
    val inputs = pack.inputs
    val sec = Files.createDirectories(pack.sec)
    val filings = recent(client.submissions(inputs.cik))
    val annual = filings.filter(_.form == "10-K").maxBy(_.filed)
    val interim = filings.filter(f => f.form == "10-Q" && f.filed.isAfter(annual.filed)).maxByOption(_.filed)
      .getOrElse(throw new IllegalStateException(s"${inputs.ticker} has no 10-Q after the ${annual.filed} 10-K"))
    val fiscalYear = annual.reportDate.getYear
    val releases = (fiscalYear - 2 to fiscalYear).toList.flatMap(year => release(filings, inputs.cik, year))
    val documents = List(
      document(inputs.cik, annual, FilingRole.AnnualReport, annual.primary),
      document(inputs.cik, annual, FilingRole.AnnualXbrl, annual.primary.stripSuffix(".htm") + "_htm.xml"),
      document(inputs.cik, annual, FilingRole.AnnualLabels, annual.primary.stripSuffix(".htm") + "_lab.xml"),
      document(inputs.cik, interim, FilingRole.InterimXbrl, interim.primary.stripSuffix(".htm") + "_htm.xml"),
      document(inputs.cik, interim, FilingRole.InterimLabels, interim.primary.stripSuffix(".htm") + "_lab.xml")
    ) ++ releases
    documents.foreach(d => Files.write(sec.resolve(d.file), client.bytes(d.url)))
    Files.write(sec.resolve(SourcePack.CompanyFacts), client.companyFactsBytes(inputs.cik))
    val manifest = SourceManifest(inputs.cik, documents ++ fetchPeers(pack))
    Files.writeString(sec.resolve(SourcePack.Manifest), JsonFormatter.Default(manifest.json))
    manifest
  }

  /** Downloads each peer's companyfacts and latest 10-K (XBRL and document), returning the filings for the manifest. */
  def fetchPeers(pack: SourcePack): List[PackFiling] = {
    val ciks = tickerCiks
    Files.createDirectories(pack.sec.resolve("peers"))
    pack.inputs.peers.map { peer =>
      val cik = peer.cik.orElse(ciks.get(peer.ticker.toUpperCase)).getOrElse(throw new NoSuchElementException(s"No CIK for peer ${peer.ticker}"))
      Files.write(pack.sec.resolve(SourcePack.peerFile(peer.ticker)), client.companyFactsBytes(cik))
      val annual = latest(cik, "10-K").getOrElse(throw new IllegalStateException(s"Peer ${peer.ticker} (CIK $cik) has no 10-K on EDGAR"))
      val xbrl = document(cik, annual, FilingRole.PeerAnnualXbrl, annual.primary.stripSuffix(".htm") + "_htm.xml")
        .copy(file = SourcePack.peerAnnualFile(peer.ticker))
      val report = document(cik, annual, FilingRole.PeerAnnualReport, annual.primary).copy(file = SourcePack.peerReportFile(peer.ticker))
      List(xbrl, report).map { filing =>
        Files.write(pack.sec.resolve(filing.file), client.bytes(filing.url))
        filing
      }
    }.flatten
  }

  /** Refreshes only the peer data of an existing pack, keeping the borrower's filings as they are. */
  def refreshPeers(pack: SourcePack): SourceManifest = {
    val peerRoles = Set(FilingRole.PeerAnnualXbrl, FilingRole.PeerAnnualReport)
    val manifest = pack.manifest.copy(filings = pack.manifest.filings.filterNot(f => peerRoles.contains(f.role)) ++ fetchPeers(pack))
    Files.writeString(pack.sec.resolve(SourcePack.Manifest), JsonFormatter.Default(manifest.json))
    manifest
  }

  /** Adds the latest proxy statement to an existing pack, for disclosures the 10-K incorporates by reference. */
  def addProxy(pack: SourcePack): PackFiling = {
    val proxy = latest(pack.inputs.cik, "DEF 14A")
      .getOrElse(throw new IllegalStateException(s"${pack.inputs.ticker} has no proxy statement on EDGAR"))
    val filing = document(pack.inputs.cik, proxy, FilingRole.Proxy, proxy.primary)
    Files.write(pack.sec.resolve(filing.file), client.bytes(filing.url))
    val manifest = pack.manifest.copy(filings = pack.manifest.filings.filterNot(_.role == FilingRole.Proxy) :+ filing)
    Files.writeString(pack.sec.resolve(SourcePack.Manifest), JsonFormatter.Default(manifest.json))
    filing
  }

  private def release(filings: List[Submission], cik: Long, year: Int): Option[PackFiling] = {
    val yearEnd = LocalDate.of(year, 12, 31)
    filings.filter(f => f.form == "8-K" && f.items.contains("2.02") && f.filed.isAfter(yearEnd) &&
      !f.filed.isAfter(yearEnd.plusDays(ReleaseWindowDays))).sortBy(_.filed).view.flatMap { filing =>
      val index = new String(client.bytes(s"${folder(cik, filing)}${filing.accession}-index.htm"), "UTF-8")
      ExhibitLink.findAllMatchIn(index).map(_.group(1)).find(_.toLowerCase.matches(""".*ex[^/]*99[^/]*1[^/]*\.htm""")).map { path =>
        PackFiling(FilingRole.EarningsRelease, "8-K", filing.accession, filing.filed, yearEnd, s"release-FY$year.htm", s"https://www.sec.gov$path")
      }
    }.headOption
  }

  private def document(cik: Long, filing: Submission, role: FilingRole, name: String): PackFiling =
    PackFiling(role, filing.form, filing.accession, filing.filed, filing.reportDate, name, s"${folder(cik, filing)}$name")

  private def folder(cik: Long, filing: Submission): String =
    s"https://www.sec.gov/Archives/edgar/data/$cik/${filing.accession.replace("-", "")}/"

  private def tickerCiks: Map[String, Long] =
    client.tickers.asObj.value.values.map(t => t("ticker").asString.toUpperCase -> t("cik_str").asLong).toMap

  /** The latest filing of a form, looking past the recent list into EDGAR's older pages when needed. */
  private def latest(cik: Long, form: String): Option[Submission] = {
    val submissions = client.submissions(cik)
    recent(submissions).filter(_.form == form).maxByOption(_.filed).orElse {
      submissions("filings")("files").asVector.view.map(f => client.json(s"https://data.sec.gov/submissions/${f("name").asString}"))
        .flatMap(page => columns(page).filter(_.form == form).maxByOption(_.filed)).headOption
    }
  }

  private def recent(submissions: Json): List[Submission] = columns(submissions("filings")("recent"))

  private def columns(listing: Json): List[Submission] = {
    val fields = List("form", "accessionNumber", "filingDate", "reportDate", "primaryDocument", "items")
      .map(name => listing(name).asVector.map(_.asString))
    fields.head.indices.toList.map { i =>
      val List(form, accession, filed, report, primary, items) = fields.map(_(i))
      Submission(form, accession, LocalDate.parse(filed), if (report.isEmpty) LocalDate.parse(filed) else LocalDate.parse(report), primary, items)
    }
  }
}
