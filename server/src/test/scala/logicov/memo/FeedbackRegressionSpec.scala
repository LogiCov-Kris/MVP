package logicov.memo

import fabric.io.JsonParser
import fabric.rw.*
import logicov.analysis.{CreditAnalysis, SampleDeals}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.time.LocalDate

/** Kris's review points on the Occidental memo, held as regressions for every borrower. */
class FeedbackRegressionSpec extends AnyFlatSpec with Matchers {
  private val template = JsonParser(new String(
    getClass.getClassLoader.getResourceAsStream("templates/public-revolver.json").readAllBytes(), StandardCharsets.UTF_8)).as[MemoTemplate]
  private val oxy = MemoContext.build(CreditAnalysis.run(SampleDeals.occidental), LocalDate.of(2026, 10, 18), "review")
  private val rendered = List(SampleDeals.kinderMorgan, SampleDeals.westlake, SampleDeals.occidental).map { record =>
    MemoRenderer.render(template, MemoContext.build(CreditAnalysis.run(record), LocalDate.of(2026, 10, 18), "review"))
  }

  "Every memo" should "use the analyst's risk-rating vocabulary" in {
    rendered.foreach { html =>
      html should not include "machine grade"
      html should not include "LogiCov assigns"
      html should not include "LogiCov calculation"
      html should include("The analyst assigns")
    }
  }

  it should "show covenant headroom in the covenant's own unit" in {
    oxy.value("covenant.headroom") should be("40.5%")
    oxy.table("covenant").rows.flatten.mkString should not include "percentage points"
  }

  "The Occidental memo" should "recommend approval at the final risk rating once the rating is resolved" in {
    oxy.value("recommendation") should be("Recommended for approval")
    oxy.value("rating.review") should be("")
    oxy.value("rating.mapping") should be("4- (BBB- / Baa3)")
  }

  it should "name the concentration it measures" in {
    oxy.table("scorecard").rows.flatten.mkString should include("Largest geographic concentration (Permian share of 2025 sales volumes) 55.0%")
  }

  it should "explain the adjacent notches as ratio thresholds and reconcile each agency" in {
    oxy.value("rating.adjacent") should include("Net debt / EBITDA at or below 1.50x")
    oxy.value("rating.adjacent") should include("give 3.69")
    oxy.value("rating.adjacent") should include("Net debt / EBITDA above 2.00x together with EBITDA / interest below 6.00x")
    oxy.value("rating.adjacent") should include("give 4.29")
    oxy.value("rating.adjacent") should include("is an override, not a calculated result")
    oxy.value("rating.differential") should include("1 notch stronger than S&P BB+; level with Moody's Baa3; 1 notch more conservative than Fitch BBB")
  }

  "The Westlake and Kinder Morgan memos" should "carry what the discovery calls add to the filings" in {
    def context(record: logicov.deal.DealRecord) = MemoContext.build(CreditAnalysis.run(record), LocalDate.of(2026, 10, 18), "review")
    val wlk = context(SampleDeals.westlake)
    wlk.table("leadership").rows.map(_.head) should contain("Jonathan H. Baksht")
    wlk.table("agency-map").rows.find(_.head == "Moody's").get should contain("November 2025 notes issuance")
    wlk.table("stress").rows.map(_.head).count(_.contains("20%")) should be(1)
    val kmi = context(SampleDeals.kinderMorgan)
    kmi.table("agency-map").rows.find(_.head == "S&P").get should contain("Stable")
    kmi.table("outlook").rows should not be empty
    kmi.table("funded-debt").rows.map(_(1)).mkString should not include "2024"
  }

  "The other memos" should "name the leverage anchor for the weaker notch, as the reference memos do" in {
    def adjacent(record: logicov.deal.DealRecord) =
      MemoContext.build(CreditAnalysis.run(record), LocalDate.of(2026, 10, 18), "review").value("rating.adjacent")
    adjacent(SampleDeals.kinderMorgan) should include("Net debt / EBITDA above 4.00x would score Leverage and FCF 5 and give 4.17")
    adjacent(SampleDeals.westlake) should include("Net debt / EBITDA above 3.00x would score Leverage and FCF 6 and give 4.35")
  }

  "The Occidental memo" should "quote the existing pricing grid rather than claim pricing is unpublished" in {
    oxy.value("facility.pricing") should include("100 to 190")
  }

  it should "label a liability total computed from assets and equity" in {
    oxy.table("balance-sheet").rows.map(_.head) should contain("Total liabilities (derived)")
  }

  it should "list the top executives with backgrounds and the discovery call's later changes" in {
    val leadership = oxy.table("leadership")
    leadership.headers should contain("Background")
    val jackson = leadership.rows.find(_.head == "Richard A. Jackson").get
    jackson.last should include("CEO since Jun 1, 2026")
  }

  it should "fill peer cells that the peers report under other standard concepts" in {
    val peers = oxy.table("peers")
    def cell(row: String, peer: String) = peers.rows.find(_.head.startsWith(row)).get(peers.headers.indexOf(peer))
    cell("Cash and cash equivalents", "CVX") should not be "—"
    cell("Total equity", "COP") should not be "—"
    cell("Cash capital expenditures", "COP") should be("12,553")
  }

  it should "show segment names as a reader would write them" in {
    SampleDeals.occidental.profile.segments.map(_.name) should be(List("Oil and Gas", "Midstream and Marketing"))
  }

  it should "show the final and calculated risk ratings with the public ratings beneath, as committees read them" in {
    oxy.value("rating.cover") should be("Final: 4- (BBB- / Baa3) (Calculated: 4 (BBB / Baa2))\nS&P BB+ / Moody's Baa3 / Fitch BBB")
  }

  it should "give peers the ratings they disclose in their own 10-K" in {
    val peers = oxy.table("peers")
    peers.rows.find(_.head == "Agency ratings").get(peers.headers.indexOf("COP")) should be("A- Stable / A2 Stable / A Stable")
  }

  it should "fold the discovery call's leadership changes into the background" in {
    oxy.table("leadership").headers should be(List("Name", "Title (filing)", "Age", "Background"))
  }

  it should "append the risk rating map, marking the final and calculated ratings" in {
    val map = oxy.table("rating-map")
    map.rows.find(_.head == "4-").get.last should be("Final")
    map.rows.find(_.head == "4").get.last should be("Calculated")
  }

  it should "test the facility's own covenant in the sensitivity table" in {
    oxy.table("stress").headers should contain("Covenant ratio")
    oxy.table("stress").rows.exists(_.head.startsWith("Reverse: equity")) should be(true)
    oxy.table("stress").rows.count(_.head.startsWith("EBITDA down")) should be(3)
  }
}
