package logicov.workspace

import fabric.rw.*
import logicov.deal.AnalystInputs
import logicov.analysis.CreditProfile

case class JudgmentRequest(revision: Long, inputs: AnalystInputs, profile: CreditProfile, reason: String, overrideReviewed: Boolean = false) derives RW
