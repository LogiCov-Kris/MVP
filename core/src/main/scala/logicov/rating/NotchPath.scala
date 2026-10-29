package logicov.rating

/** The anchor moves that take the composite to `target`, and the component scores that change. */
case class NotchPath(target: Grade, threshold: BigDecimal, moves: List[AnchorMove], composite: BigDecimal, changed: List[ComponentScore])
