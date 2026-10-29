package logicov.analysis

import fabric.rw.*
import logicov.source.Source

case class Segment(name: String,
                   sales: Map[Int, BigDecimal],
                   earnings: Map[Int, BigDecimal],
                   priceLinked: Boolean,
                   source: Source) derives RW
