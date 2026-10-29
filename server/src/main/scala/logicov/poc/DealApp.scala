package logicov.poc

import fabric.io.JsonFormatter
import fabric.rw.*
import logicov.ai.LogiCovSigil
import logicov.deal.{DealBuilder, OfficerExtractor, RatingsExtractor}
import logicov.edgar.{EdgarClient, FilingRole, OfficerSection, RatingsDisclosure, SourcePack, SourcePackFetcher}
import logicov.memo.Format

import spice.http.client.HttpClient

import java.nio.file.{Files, Path}

/**
 * Builds a deal record from a source pack: `DealApp <pack dir> <output dir> [--fetch] [--peers] [--officers] [--ratings]`.
 * With --fetch the SEC filings are downloaded into the pack first; --peers refreshes only the peers' data; --officers
 * extracts the executive officers and --ratings the credit ratings each peer discloses in its 10-K.
 * EDGAR_USER_AGENT names the requester, as the SEC requires.
 */
object DealApp {
  private val FetchFlag = "--fetch"
  private val OfficersFlag = "--officers"
  private val PeersFlag = "--peers"
  private val RatingsFlag = "--ratings"

  def main(args: Array[String]): Unit = {
    val (flags, positional) = args.toList.partition(_.startsWith("--"))
    if (positional.size != 2) throw new IllegalArgumentException(s"Usage: DealApp <pack dir> <output dir> [$FetchFlag] [$PeersFlag] [$OfficersFlag] [$RatingsFlag]")
    val dir = Path.of(positional.head)
    lazy val fetcher = new SourcePackFetcher(new EdgarClient(
      sys.env.getOrElse("EDGAR_USER_AGENT", throw new IllegalStateException("Set EDGAR_USER_AGENT to fetch from EDGAR"))))
    if (flags.contains(FetchFlag)) fetcher.fetch(SourcePack(dir))
    if (flags.contains(PeersFlag)) fetcher.refreshPeers(SourcePack(dir))
    val extractions = List(OfficersFlag -> (() => extractOfficers(dir, fetcher)), RatingsFlag -> (() => extractPeerRatings(dir)))
      .collect { case (flag, run) if flags.contains(flag) => run }
    if (extractions.nonEmpty) {
      LogiCovSigil.instance.sync()
      try extractions.foreach(_())
      finally {
        try LogiCovSigil.shutdown.sync()
        finally HttpClient.dispose().sync()
      }
    }
    val record = DealBuilder.build(SourcePack(dir))
    val output = Files.createDirectories(Path.of(positional(1))).resolve(s"${record.ticker.toLowerCase}-deal.json")
    Files.writeString(output, JsonFormatter.Default(record.json))
    scribe.info(s"${record.borrower}: $output")
  }

  /** Reads officers from the 10-K, or from the proxy statement when the 10-K incorporates them by reference. */
  private def extractOfficers(dir: Path, fetcher: => SourcePackFetcher): Unit = {
    val pack = SourcePack(dir)
    val annual = pack.manifest.one(FilingRole.AnnualReport)
    val located = OfficerSection.find(pack.text(annual)).map(_ -> annual).orElse {
      val proxy = pack.manifest.all(FilingRole.Proxy).headOption.getOrElse(fetcher.addProxy(pack))
      OfficerSection.find(SourcePack(dir).text(proxy)).map(_ -> proxy)
    }
    located match {
      case Some((section, filing)) =>
        val officers = OfficerExtractor.extract(section, filing.form, filing.accession).sync()
        pack.writeOfficers(officers)
        scribe.info(s"${officers.size} verified officers from ${filing.form} ${filing.accession}")
      case None => scribe.warn(s"No executive-officer listing found for ${pack.inputs.ticker}")
    }
  }

  /** Reads each peer's self-disclosed credit ratings from its 10-K; peers that disclose none get an empty list. */
  private def extractPeerRatings(dir: Path): Unit = {
    val pack = SourcePack(dir)
    pack.inputs.peers.foreach { peer =>
      pack.peerReport(peer.ticker).foreach { filing =>
        val passages = RatingsDisclosure.passages(pack.text(filing))
        val ratings = RatingsExtractor.extract(passages, filing.form, filing.accession, s"${filing.form} filed ${Format.date(filing.filed)}").sync()
        pack.writePeerRatings(peer.ticker, ratings)
        scribe.info(s"${peer.ticker}: ${if (ratings.isEmpty) "no ratings disclosed" else ratings.map(r => s"${r.house} ${r.rating}").mkString(", ")}")
      }
    }
  }
}
