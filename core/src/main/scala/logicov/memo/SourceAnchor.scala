package logicov.memo

import logicov.grid.{LineItem, Period}

object SourceAnchor {
  def id(entity: String, item: LineItem, period: Period): String =
    s"$entity-$item-${period.start.fold("instant")(_.toString)}-${period.end}"
}
