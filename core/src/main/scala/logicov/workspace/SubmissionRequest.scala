package logicov.workspace

import fabric.rw.*

case class SubmissionRequest(revision: Long, recommendation: CreditRecommendation, rationale: String) derives RW
