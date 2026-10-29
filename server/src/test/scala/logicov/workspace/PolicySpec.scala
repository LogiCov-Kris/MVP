package logicov.workspace

import logicov.analysis.CreditAnalysis
import logicov.analysis.SampleDeals
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PolicySpec extends AnyFlatSpec with Matchers {
  "Institution policy" should "distinguish unconfigured policy and unavailable financial inputs from passing rules" in {
    PolicyEngine.evaluate(None, None).head.status should be(PolicyStatus.NotConfigured)
    val rule = PolicyRule("leverage", "Maximum net leverage", PolicyMetric.NetLeverage, PolicyOperator.AtMost, 3)
    val policy = PolicySet("Test policy", "1", "Test credit manual", List(rule))
    PolicyEngine.evaluate(Some(policy), None).head.status should be(PolicyStatus.Unavailable)
    intercept[IllegalArgumentException](policy.copy(rules = List(rule, rule)).validate())
  }

  it should "evaluate ratios on their own basis and include exact thresholds" in {
    val record = SampleDeals.occidental
    val leverage = CreditAnalysis.run(record).ratios.netLeverage
    val policy = PolicySet("Test policy", "1", "Test credit manual", List(
      PolicyRule("equal", "Inclusive maximum", PolicyMetric.NetLeverage, PolicyOperator.AtMost, leverage),
      PolicyRule("breach", "Exceeded maximum", PolicyMetric.NetLeverage, PolicyOperator.AtMost, leverage - 1),
      PolicyRule("capital", "Capitalization", PolicyMetric.DebtToCapitalization, PolicyOperator.AtMost, 1)))
    val results = PolicyEngine.evaluate(Some(policy), Some(record))
    results.map(_.status) should be(List(PolicyStatus.Pass, PolicyStatus.Breach, PolicyStatus.Pass))
    results.last.actual should be(Some(CreditAnalysis.run(record).ratios.debtToCapitalization))
  }
}
