package logicov.workspace

import fabric.rw.*

case class ApprovalRecord(actor: String, at: String, revision: Long, exception: String) derives RW
