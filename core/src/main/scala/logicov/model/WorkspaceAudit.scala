package logicov.model

import fabric.rw.*

case class WorkspaceAudit(action: WorkspaceAction, actor: String, at: Long, detail: String) derives RW
