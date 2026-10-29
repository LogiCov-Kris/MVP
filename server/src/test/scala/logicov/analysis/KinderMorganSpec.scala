package logicov.analysis

import logicov.grid.{LineItem, Period}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.math.BigDecimal.RoundingMode

class KinderMorganSpec extends AnyFlatSpec with Matchers {
  private val record = SampleDeals.kinderMorgan
  private val analysis = CreditAnalysis.run(record)
  private val periods = record.periods

  "The call script" should "supply the facility and non-GAAP inputs" in {
    record.profile.commitment.value should be(BigDecimal(3500))
    record.profile.covenant.threshold.value should be(BigDecimal(5.50))
    record.profile.ltmEbitda.map(_.value) should be(Some(BigDecimal(9000)))
    record.profile.availability.value should be(BigDecimal(3200))
  }

  "The Golden Grid" should "match the filed figures in the memo" in {
    val grid = record.grid
    grid(LineItem.Revenue, Period.fiscalYear(2025)) should be(BigDecimal(16937))
    grid(LineItem.NetIncomeAttributable, Period.fiscalYear(2025)) should be(BigDecimal(3056))
    grid(LineItem.OperatingCashFlow, Period.fiscalYear(2023)) should be(BigDecimal(6491))
    grid(LineItem.CashCapex, Period.fiscalYear(2025)) should be(BigDecimal(3026))
    grid(LineItem.DividendsPaid, Period.fiscalYear(2025)) should be(BigDecimal(2604))
    grid(LineItem.InterestExpense, Period.fiscalYear(2025)) should be(BigDecimal(1801))
    grid(LineItem.Revenue, periods.interim) should be(BigDecimal(9305))
    grid(LineItem.Cash, periods.latest) should be(BigDecimal(89))
    grid(LineItem.CurrentDebt, periods.latest) should be(BigDecimal(2443))
    grid(LineItem.DebtPrincipal, periods.latest) should be(BigDecimal(32144))
    grid(LineItem.TotalEquity, periods.latest) should be(BigDecimal(32879))
  }

  "The key ratios" should "reproduce the memo" in {
    val ratios = analysis.ratios
    ratios.netDebt should be(BigDecimal(32055))
    round(ratios.netLeverage) should be(BigDecimal("3.56"))
    round(ratios.interestCoverage, 1) should be(BigDecimal("5.0"))
    round(ratios.fcfToDebt, 3) should be(BigDecimal("0.105"))
    round(ratios.liquidityCover) should be(BigDecimal("1.35"))
    round(ratios.debtToCapitalization, 3) should be(BigDecimal("0.494"))
  }

  "The rating" should "score grade 4 (BBB / Baa2) from a 3.97 composite" in {
    val rating = analysis.rating
    rating.components.map(_.score) should be(List(4, 4, 3, 4, 4, 5))
    rating.composite should be(BigDecimal("3.97"))
    rating.grade.mapping should be("4 (BBB / Baa2)")
    rating.grade.pd should be(BigDecimal("0.0014"))
    round(rating.expectedLoss) should be(BigDecimal("1.96"))
    record.profile.agencyRatings.map(a => rating.notchesVersus(a.rating)) should be(List(1, 1, 1))
  }

  "The covenant headroom" should "match the LogiCov proxy" in {
    val covenant = analysis.covenant
    round(covenant.headroom) should be(BigDecimal("1.94"))
    covenant.ebitdaAtThreshold.map(round(_, 0)) should be(Some(BigDecimal(5828)))
    round(covenant.debtCapacity / 1000, 1) should be(BigDecimal("17.4"))
  }

  "The stress grid" should "reproduce the memo scenarios" in {
    val stress = analysis.stress.get
    round(stress.run(StressScenario("Base")).cashAfterCapexAndDividends, 0) should be(BigDecimal(287))

    val severe = stress.run(StressScenario("Severe", ebitdaChange = -0.15))
    round(severe.leverage.get) should be(BigDecimal("4.19"))
    severe.covenantHeadroom.map(round(_)) should be(Some(BigDecimal("1.31")))
    round(severe.interestCoverage.get, 1) should be(BigDecimal("4.2"))
    round(severe.dividendCover) should be(BigDecimal("1.75"))
    round(severe.cashAfterCapexAndDividends, 0) should be(BigDecimal(-1063))

    val combined = stress.run(StressScenario("Combined", ebitdaChange = -0.15, addedDebt = 5000))
    round(combined.leverage.get) should be(BigDecimal("4.84"))
    combined.covenantHeadroom.map(round(_)) should be(Some(BigDecimal("0.66")))

    val reverse = stress.run(StressScenario("Reverse", reverseToCovenant = true))
    round(reverse.ebitda, 0) should be(BigDecimal(5828))
    round(reverse.interestCoverage.get, 1) should be(BigDecimal("3.2"))
    round(reverse.dividendCover) should be(BigDecimal("1.05"))
    round(reverse.cashAfterCapexAndDividends, 0) should be(BigDecimal(-2885))
  }

  private def round(value: BigDecimal, scale: Int = 2): BigDecimal = value.setScale(scale, RoundingMode.HALF_UP)
}
