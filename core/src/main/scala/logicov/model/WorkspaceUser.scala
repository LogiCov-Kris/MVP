package logicov.model

import fabric.rw.*

case class WorkspaceUser(email: String, role: UserRole, active: Boolean) derives RW
