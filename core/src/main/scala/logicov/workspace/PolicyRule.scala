package logicov.workspace

import fabric.rw.*

case class PolicyRule(id: String, description: String, metric: PolicyMetric, operator: PolicyOperator, threshold: BigDecimal) derives RW
