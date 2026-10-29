package logicov.grid

import fabric.rw.*

case class GoldenGrid(entity: String, values: List[GridValue]) derives RW {
  def get(item: LineItem, period: Period): Option[GridValue] = values.find(v => v.item == item && v.period == period)

  def apply(item: LineItem, period: Period): BigDecimal = get(item, period).map(_.value).getOrElse {
    throw new NoSuchElementException(s"$item for ${period.label} is not in the grid for $entity")
  }

  def orZero(item: LineItem, period: Period): BigDecimal = get(item, period).map(_.value).getOrElse(BigDecimal(0))
}
