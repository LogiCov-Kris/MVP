package logicov.deal

import fabric.rw.*
import logicov.source.Source

/** An executive officer as listed in the filing; every field is verbatim from `source`. */
case class ExecutiveOfficer(name: String, title: String, age: Option[Int], background: String, source: Source) derives RW
