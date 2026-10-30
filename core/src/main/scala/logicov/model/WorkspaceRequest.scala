package logicov.model

import fabric.rw.*

case class WorkspaceRequest(action: WorkspaceAction, name: String, id: String = "",
                            administratorEmail: String = "", active: Boolean = true, revision: Long = 0) derives RW
