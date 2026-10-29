package logicov.grid

import fabric.rw.*
import logicov.source.Source

case class GridValue(item: LineItem, period: Period, value: BigDecimal, source: Source) derives RW
