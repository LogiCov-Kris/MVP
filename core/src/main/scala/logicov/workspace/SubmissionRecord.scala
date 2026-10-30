package logicov.workspace

import fabric.rw.*

case class SubmissionRecord(actor: String, at: String, recommendation: CreditRecommendation, rationale: String) derives RW
