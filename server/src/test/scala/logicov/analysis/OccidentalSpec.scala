package logicov.analysis

import logicov.grid.{LineItem, Period}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.math.BigDecimal.RoundingMode

class OccidentalSpec extends AnyFlatSpec with Matchers {
  private val record = SampleDeals.occidental
  private val analysis = CreditAnalysis.run(record)

  "The Golden Grid" should "carry the filed figures" in {
    val grid = record.grid
    grid(LineItem.Revenue, Period.fiscalYear(2025)) should be(BigDecimal(21593))
    grid(LineItem.NetIncomeAttributable, Period.fiscalYear(2025)) should be(BigDecimal(2326))
    grid(LineItem.InterestExpense, Period.fiscalYear(2025)) should be(BigDecimal(1079))
    grid(LineItem.DebtPrincipal, Period.yearEnd(2025)) should be(BigDecimal(20427))
    grid(LineItem.FinanceLeases, Period.yearEnd(2025)) should be(BigDecimal(999))
    grid(LineItem.CurrentDebt, record.periods.latest) should be(BigDecimal(203))
  }

  "The key ratios" should "match the memo's Grid-basis figures" in {
    val ratios = analysis.ratios
    round(ratios.netLeverage) should be(BigDecimal("1.61"))
    round(ratios.fcfToDebt, 3) should be(BigDecimal("0.247"))
    round(ratios.interestCoverage, 1) should be(BigDecimal("11.2"))
    round(ratios.liquidityCover, 1) should be(BigDecimal("40.9"))
  }

  "The rating" should "calculate risk rating 4 and apply the one-notch override to 4-" in {
    val rating = analysis.rating
    rating.components.map(_.score) should be(List(4, 3, 3, 5, 6, 3))
    rating.composite should be(BigDecimal("3.89"))
    rating.machineGrade.mapping should be("4 (BBB / Baa2)")
    rating.grade.mapping should be("4- (BBB- / Baa3)")
    round(rating.expectedLoss) should be(BigDecimal("3.32"))
    record.profile.agencyRatings.map(a => rating.notchesVersus(a.rating)) should be(List(-1, 0, 1))
  }

  "The debt-to-capitalization covenant" should "match the memo" in {
    round(analysis.covenant.actual, 3) should be(BigDecimal("0.245"))
    analysis.covenant.ebitdaAtThreshold should be(None)
  }

  private def round(value: BigDecimal, scale: Int = 2): BigDecimal = value.setScale(scale, RoundingMode.HALF_UP)
}
