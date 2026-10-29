package logicov.workspace

import fabric.rw.*

case class RecommendationRequest(revision: Long, recommendation: CreditRecommendation, rationale: String) derives RW
