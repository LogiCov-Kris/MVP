package logicov.analysis

import fabric.rw.*
import logicov.source.Sourced

case class Covenant(kind: CovenantKind, threshold: Sourced[BigDecimal], indicative: Boolean = false) derives RW
