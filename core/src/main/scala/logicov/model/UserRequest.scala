package logicov.model

import fabric.rw.*

case class UserRequest(action: UserAction, email: String, role: UserRole = UserRole.Analyst,
                       active: Boolean = true, revision: Long = 0) derives RW
