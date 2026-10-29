package logicov.workspace

import fabric.rw.*

case class DealActionRequest(revision: Long, reason: String = "") derives RW
