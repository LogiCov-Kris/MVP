package logicov.analysis

import logicov.grid.{LineItem, Period}
import logicov.rating.Ladder
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.math.BigDecimal.RoundingMode

class WestlakeSpec extends AnyFlatSpec with Matchers {
  private val record = SampleDeals.westlake
  private val analysis = CreditAnalysis.run(record)

  "The Golden Grid" should "carry the filed figures" in {
    val grid = record.grid
    grid(LineItem.Revenue, Period.fiscalYear(2025)) should be(BigDecimal(11170))
    grid(LineItem.InterestExpense, Period.fiscalYear(2025)) should be(BigDecimal(171))
    grid(LineItem.DebtPrincipal, Period.yearEnd(2025)) should be(BigDecimal(5680))
    grid(LineItem.Cash, Period.yearEnd(2025)) should be(BigDecimal(2724))
    grid(LineItem.DebtPrincipal, record.periods.latest) should be(BigDecimal(5160))
  }

  "The segment shares" should "fall back to sales because PEM earnings are negative" in {
    record.profile.largestShare.value.setScale(3, BigDecimal.RoundingMode.HALF_UP) should be(BigDecimal("0.646"))
    record.profile.priceLinkedShare.value.setScale(3, BigDecimal.RoundingMode.HALF_UP) should be(BigDecimal("0.629"))
    record.profile.ladder should be(Ladder.A)
  }

  "The key ratios" should "match the memo's Grid-basis figures" in {
    val ratios = analysis.ratios
    round(ratios.netLeverage) should be(BigDecimal("2.58"))
    round(ratios.fcfToDebt, 3) should be(BigDecimal("0.063"))
    round(ratios.interestCoverage, 1) should be(BigDecimal("6.7"))
    round(ratios.scaleEbitda, 0) should be(BigDecimal(2006))
    ratios.liquidityCover should be(BigDecimal(5))
  }

  "The rating" should "score grade 4 (BBB / Baa2) from a 4.15 composite" in {
    val rating = analysis.rating
    rating.components.map(_.score) should be(List(5, 3, 4, 4, 6, 3))
    rating.composite should be(BigDecimal("4.15"))
    rating.grade.mapping should be("4 (BBB / Baa2)")
    round(rating.expectedLoss) should be(BigDecimal("0.84"))
    record.profile.agencyRatings.map(a => rating.notchesVersus(a.rating)) should be(List(0, 0, 0))
  }

  "The indicative covenant" should "measure headroom to 3.5x on the Grid basis" in {
    analysis.covenant.covenant.indicative should be(true)
    round(analysis.covenant.headroom) should be(BigDecimal("0.92"))
  }

  private def round(value: BigDecimal, scale: Int = 2): BigDecimal = value.setScale(scale, RoundingMode.HALF_UP)
}
