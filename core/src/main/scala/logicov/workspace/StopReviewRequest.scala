package logicov.workspace

import fabric.rw.*

case class StopReviewRequest(revision: Long, reviews: List[StopReview]) derives RW
