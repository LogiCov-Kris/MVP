package logicov.analysis

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SensitivitySpec extends AnyFlatSpec with Matchers {
  "EBITDA sensitivities" should "test capitalization rather than invent an EBITDA covenant" in {
    val analysis = CreditAnalysis.run(SampleDeals.occidental)
    val stress = analysis.stress.get
    List(BigDecimal("0.20"), BigDecimal("0.30"), BigDecimal("0.40")).foreach { reduction =>
      val row = stress.run(StressScenario("Sensitivity", ebitdaChange = -reduction))
      row.ebitda should be(analysis.ratios.ebitda * (1 - reduction))
      row.covenantHeadroom should be(Some(analysis.covenant.headroom))
      row.cashAfterCapexAndDividends should be(stress.operatingCashFlow - stress.ebitda * reduction - stress.capex - stress.dividends)
    }
  }

  it should "reduce leverage covenant headroom and identify a breach" in {
    val analysis = CreditAnalysis.run(SampleDeals.kinderMorgan)
    val row = analysis.stress.get.run(StressScenario("EBITDA down 40%", ebitdaChange = BigDecimal("-0.4")))
    row.covenantHeadroom.get should be < BigDecimal(0)
  }

  "Revenue sensitivities" should "require and apply an explicit cost response" in {
    intercept[IllegalArgumentException] { StressScenario("Revenue", revenueChange = Some(BigDecimal("-0.2"))) }
    val stress = CreditAnalysis.run(SampleDeals.occidental).stress.get
    val row = stress.run(StressScenario("Revenue", revenueChange = Some(BigDecimal("-0.2")), costFlex = Some(BigDecimal("0.5"))))
    row.ebitda should be(stress.ebitda - stress.revenue * BigDecimal("0.1"))
    intercept[IllegalArgumentException] { StressScenario("Mixed", ebitdaChange = BigDecimal("-0.2"), revenueChange = Some(BigDecimal("-0.2")), costFlex = Some(BigDecimal("0.5"))) }
  }

  "Capitalization stress" should "reach the threshold through an explicit equity loss" in {
    val test = CapitalizationTest(BigDecimal("0.65"), 13743, 42381)
    test.headroom(0, -test.equity * test.equityDeclineToThreshold).abs should be < BigDecimal("0.00000001")
    intercept[IllegalArgumentException] { test.actual(0, -60000) }
  }

  "Uninterpretable scenarios" should "report no leverage or coverage instead of a misleading ratio" in {
    val row = CreditAnalysis.run(SampleDeals.kinderMorgan).stress.get.run(StressScenario("EBITDA eliminated", ebitdaChange = -1))
    row.leverage should be(None)
    row.interestCoverage should be(None)
    row.covenantHeadroom should be(None)
  }
}
