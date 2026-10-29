package logicov.grid

import fabric.rw.*
import logicov.source.Source

case class GridCorrection(item: LineItem, period: Period, expected: BigDecimal, value: BigDecimal,
                          source: Source, reason: String, analyst: String, recordedAt: String) derives RW
