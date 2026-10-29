package logicov.rating

/**
 * A scorecard ratio and its thresholds: `atMost` anchors score better as the value falls (leverage, shares), the
 * others as it rises (coverage, FCF, scale).
 */
case class RatioAnchor(name: String,
                       value: BigDecimal,
                       thresholds: List[BigDecimal],
                       atMost: Boolean,
                       format: BigDecimal => String,
                       set: (RatingInputs, BigDecimal) => RatingInputs) {
  private val Nudge = BigDecimal("0.0001")

  def score: Int = if (atMost) Anchors.atMost(value, thresholds) else Anchors.atLeast(value, thresholds)

  /** The move `steps` scores in the given direction, when the thresholds allow it. */
  def step(stronger: Boolean, steps: Int): Option[AnchorMove] = {
    val index = if (stronger) score - 1 - steps else score - 2 + steps
    thresholds.lift(index).map { t =>
      val (moved, phrase) = (atMost, stronger) match {
        case (true, true) => (t, "at or below")
        case (true, false) => (t + Nudge, "above")
        case (false, true) => (t, "at or above")
        case (false, false) => (t - Nudge, "below")
      }
      AnchorMove(s"$name $phrase ${format(t)}", inputs => set(inputs, moved))
    }
  }
}
