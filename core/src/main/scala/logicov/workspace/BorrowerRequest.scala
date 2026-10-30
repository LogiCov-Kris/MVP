package logicov.workspace

import fabric.rw.*

case class BorrowerRequest(revision: Long, name: String, reason: String) derives RW
