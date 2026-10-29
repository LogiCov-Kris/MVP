package logicov.workspace

import logicov.analysis.CreditAnalysis
import logicov.deal.DealRecord

object PolicyEngine {
  def evaluate(policy: Option[PolicySet], record: Option[DealRecord]): List[PolicyResult] = policy match {
    case None => List(PolicyResult("unconfigured", "Institution policy has not been configured", PolicyStatus.NotConfigured, None, None))
    case Some(set) =>
      set.validate()
      val analysis = record.map(CreditAnalysis.run)
      set.rules.map { rule =>
        val value = analysis.map { a => rule.metric match {
          case PolicyMetric.NetLeverage => a.ratios.netLeverage
          case PolicyMetric.InterestCoverage => a.ratios.interestCoverage
          case PolicyMetric.LiquidityCover => a.ratios.liquidityCover
          case PolicyMetric.DebtToCapitalization => a.ratios.debtToCapitalization
          case PolicyMetric.CompositeRating => a.rating.composite
          case PolicyMetric.NetDebt => a.ratios.netDebt
          case PolicyMetric.Ebitda => a.ratios.ebitda
        }}
        val status = value.fold(PolicyStatus.Unavailable) { actual =>
          val passes = rule.operator match {
            case PolicyOperator.AtMost => actual <= rule.threshold
            case PolicyOperator.AtLeast => actual >= rule.threshold
          }
          if (passes) PolicyStatus.Pass else PolicyStatus.Breach
        }
        PolicyResult(rule.id, rule.description, status, value, Some(rule.threshold))
      }
  }
}
