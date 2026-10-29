package logicov.ingest

import logicov.analysis.{CreditAnalysis, SampleDeals}
import logicov.deal.DealCorrections
import logicov.grid.{GridCorrection, LineItem, Period}
import logicov.source.Source
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DealCorrectionsSpec extends AnyFlatSpec with Matchers {
  "Deal corrections" should "update the OXY spread and dependent ratios while preserving the original evidence and override" in {
    val record = SampleDeals.occidental
    val period = Period.fiscalYear(2025)
    val original = record.grid(LineItem.PretaxIncome, period)
    val correction = GridCorrection(LineItem.PretaxIncome, period, original, original + 100,
      Source.Filing("Synthetic correction example, not a source defect"), "Demonstrate recalculation", "Test analyst", "2026-10-26T12:00:00Z")
    val result = DealCorrections(record, List(correction))
    result.profile.ebitdaFor(2025) should be(record.profile.ebitdaFor(2025).map(_ + 100))
    CreditAnalysis.run(result).ratios.ebitda should be(CreditAnalysis.run(record).ratios.ebitda + 100)
    result.profile.ratingOverride should be(record.profile.ratingOverride)
    result.profile.commodityModifier should be(record.profile.commodityModifier)
    result.revisions.head.original.value should be(original)
    result.revisions.head.original.source should be(record.grid.get(LineItem.PretaxIncome, period).map(_.source).getOrElse(fail("Missing original")))
    intercept[IllegalArgumentException](DealCorrections(result, List(correction)))
    val second = correction.copy(expected = original + 100, value = original + 200)
    DealCorrections(result, List(second)).profile.ebitdaFor(2025) should be(record.profile.ebitdaFor(2025).map(_ + 200))
  }

  it should "recalculate cash and leverage without changing independently sourced LTM EBITDA" in {
    val record = SampleDeals.kinderMorgan
    val period = record.periods.latest
    val original = record.grid(LineItem.Cash, period)
    val result = DealCorrections(record, List(GridCorrection(LineItem.Cash, period, original, original + 20,
      Source.Filing("Synthetic cash reconciliation"), "Correction demonstration", "Test analyst", "2026-10-26T12:00:00Z")))
    result.profile.ltmEbitda should be(record.profile.ltmEbitda)
    CreditAnalysis.run(result).ratios.netDebt should be(CreditAnalysis.run(record).ratios.netDebt - 20)
    CreditAnalysis.run(result).ratios.netLeverage should be < CreditAnalysis.run(record).ratios.netLeverage
  }
}
