package logicov.memo

import fabric.io.JsonParser
import fabric.rw.*
import logicov.analysis.{CreditAnalysis, SampleDeals}
import logicov.edgar.{FilingRole, OfferingRatings, OfficerSection}
import logicov.grid.{LineItem, Period}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.time.LocalDate

class LatestFeedbackSpec extends AnyFlatSpec with Matchers {
  private val template = JsonParser(new String(getClass.getClassLoader.getResourceAsStream(
    "templates/public-revolver.json").readAllBytes(), StandardCharsets.UTF_8)).as[MemoTemplate]
  private val kmi = SampleDeals.kinderMorgan
  private val context = MemoContext.build(CreditAnalysis.run(kmi), LocalDate.of(2026, 10, 27), "review")

  "The financial summary" should "use the filing's common-stock allocation consistently across annual periods" in {
    kmi.periods.fiscalYears.map(y => kmi.grid(LineItem.CommonIncome, Period.fiscalYear(y))) should be(List(
      BigDecimal(2377), BigDecimal(2598), BigDecimal(3040)))
    context.table("financials").rows.find(_.head == "Net income available to common").toList.flatMap(_.tail) should not contain "—"
    kmi.grid.get(LineItem.CommonIncome, Period.fiscalYear(2025)).map(_.source.describe).getOrElse("") should include("ClassPMember")
  }

  it should "put period-aligned covenant component rows inside the financial summary without a separate table or ratio" in {
    val table = context.table("financials")
    table.rows.find(_.head == "Net debt").map(_.tail) should be(Some(List("31,846", "31,700", "31,760", "32,055")))
    table.rows.find(_.head == "EBITDA").map(_.tail) should be(Some(List("7,561", "7,938", "8,391", "9,000 (LTM)")))
    table.rows.forall(_.size == table.headers.size) should be(true)
    val html = MemoRenderer.render(template, context)
    html should not include "id=\"financial-covenant-components\""
    table.rows.map(_.head) should not contain "Net debt / EBITDA"
    val oxy = FinancialTables.build(CreditAnalysis.run(SampleDeals.occidental))("financials")
    oxy.rows.find(_.head == "Debt (carrying amount)").map(_.tail) should be(Some(List("19,738", "26,116", "22,396", "13,743")))
    oxy.rows.find(_.head == "Total capitalization").map(_.last) should be(Some("56,124"))
    oxy.rows.exists(_.head.startsWith("EBITDA")) should be(false)
  }

  it should "label Westlake's EBITDA basis rather than passing annual EBITDA off as interim earnings" in {
    val wlk = MemoContext.build(CreditAnalysis.run(SampleDeals.westlake), LocalDate.of(2026, 10, 27), "review")
    wlk.table("covenant").rows.find(_.head == "Measured at").map(_(1)).getOrElse("") should not include "2026"
    wlk.table("financials").rows.find(_.head == "Net debt").map(_.last) should be(Some("3,517"))
    wlk.table("financials").rows.find(_.head == "EBITDA").map(_.last) should be(Some("1,144 (FY2025)"))
  }

  it should "use matching financial column widths for KMI and Oxy and retain citations on the added rows" in {
    val html = MemoRenderer.render(template, context)
    html should include("<colgroup><col style=\"width:42.0%\"><col style=\"width:14.5%\">")
    val table = context.table("financials")
    val row = table.rows.indexWhere(_.head == "Net debt")
    (1 until table.headers.size).foreach { column => table.links((row, column)) should endWith("#sources") }
    val oxy = MemoRenderer.render(template, MemoContext.build(CreditAnalysis.run(SampleDeals.occidental), LocalDate.of(2026, 10, 27), "review"))
    oxy should include("<colgroup><col style=\"width:42.0%\"><col style=\"width:14.5%\">")
  }

  "Director-officer backgrounds" should "come verbatim from the proxy and leave unrelated names untouched" in {
    val pack = SampleDeals.pack("kmi")
    val text = pack.text(pack.manifest.one(FilingRole.Proxy))
    kmi.officers.take(2).foreach { officer =>
      officer.background should not be empty
      OfficerSection.plain(text) should include(officer.background)
    }
    OfficerSection.biography(text, "Unlisted Person") should be(None)
    kmi.officers.head.background should include("Chief Executive Officer of KMI from 1999")
  }

  "The risk rating map" should "bold only matching external agency cells" in {
    val table = context.table("rating-map")
    val expected = kmi.profile.agencyRatings.filter(a => Set("S&P", "Moody's").contains(a.house)).map(_.rating).toSet
    table.boldCells.map((r, c) => table.rows(r)(c)) should be(expected)
    val html = MemoRenderer.render(template, context)
    expected.foreach(rating => html should include(s"<strong>$rating</strong>"))
  }

  "Offering ratings" should "retain agency order, outlook, date and the expected-notes qualification" in {
    val peers = SampleDeals.westlake.peers.map(p => p.ticker -> p.disclosedRatings).toMap
    peers("EMN").map(r => (r.house, r.rating, r.outlook)) should contain allOf (
      ("Moody's", "Baa2", "Stable"), ("S&P", "BBB", "Negative"))
    peers("LYB").map(r => (r.house, r.rating, r.outlook)) should contain allOf (
      ("Moody's", "Baa2", "Negative"), ("S&P", "BBB", "Negative"), ("Fitch", "BBB", "Stable"))
    peers("LYB").foreach(_.asOf should include("expected parent-guaranteed notes ratings"))
    val pack = SampleDeals.pack("wlk")
    val filing = pack.manifest.all(FilingRole.PeerRatingsOffering).head
    an[IllegalArgumentException] should be thrownBy OfferingRatings.extract("Investment grade means BBB- or Baa3", filing)
  }

  "Retained narratives" should "reject incomplete sets and figures absent from the updated record" in {
    an[IllegalArgumentException] should be thrownBy NarrativeGenerator.reuse(template, context, Map.empty)
    val drafts = template.sections.zipWithIndex.flatMap { (section, s) =>
      section.blocks.zipWithIndex.collect { case (MemoBlock.Narrative(_, guidance), b) =>
        MemoRenderer.narrativeKey(s, b) -> (if (guidance.contains("Exactly six paragraphs"))
          List.fill(6)("Risk: Exposure. Mitigant: Liquidity.").mkString("\n\n") else "The analyst reviews the financial statements.")
      }
    }.toMap
    NarrativeGenerator.reuse(template, context, drafts).size should be(drafts.size)
    an[IllegalArgumentException] should be thrownBy NarrativeGenerator.reuse(template, context,
      drafts.updated(drafts.keys.head, "Revenue was $987654321 million."))
  }
}
