package logicov.workspace

import fabric.rw.*
import logicov.grid.{LineItem, Period}

case class GridAdditionRequest(revision: Long, item: LineItem, period: Period, value: BigDecimal,
                               documentId: String, citation: String, reason: String) derives RW
