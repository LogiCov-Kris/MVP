package logicov.analysis

import fabric.rw.*
import logicov.source.Source

case class AnnualFigure(year: Int, value: BigDecimal, source: Source) derives RW
