package logicov.workspace

import fabric.rw.*

case class WorkspaceEvent(at: String, actor: String, action: String, detail: String, revision: Long) derives RW
