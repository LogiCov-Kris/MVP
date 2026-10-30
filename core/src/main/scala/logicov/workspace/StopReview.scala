package logicov.workspace

import fabric.rw.*

case class StopReview(topic: ReviewTopic, status: ReviewStatus, evidence: String) derives RW

object StopReview {
  def validate(reviews: List[StopReview]): Unit = {
    require(reviews.map(_.topic).distinct.size == reviews.size && reviews.size <= ReviewTopic.values.length, "Duplicate or excessive review checks")
    require(reviews.forall(r => r.evidence.length <= 2000 && (r.status == ReviewStatus.Pending || r.evidence.trim.nonEmpty)), "Document the evidence for each reviewed check")
  }

  def requireClear(reviews: List[StopReview]): Unit = {
    validate(reviews)
    require(ReviewTopic.values.forall(topic => reviews.exists(r => r.topic == topic && r.status == ReviewStatus.Clear)),
      "Complete all required checks and resolve every stop condition before approval or generation")
  }
}
