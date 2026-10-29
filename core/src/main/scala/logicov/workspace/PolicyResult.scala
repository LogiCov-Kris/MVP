package logicov.workspace

import fabric.rw.*

case class PolicyResult(rule: String, description: String, status: PolicyStatus, actual: Option[BigDecimal], threshold: Option[BigDecimal]) derives RW
