package logicov.rating

/** A ratio moved past one or more of its anchor thresholds. */
case class AnchorMove(description: String, change: RatingInputs => RatingInputs)
