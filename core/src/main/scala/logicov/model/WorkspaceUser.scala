package logicov.model

import fabric.rw.*

case class WorkspaceUser(email: String, role: UserRole, active: Boolean, revision: Long = 0,
                         lastSignIn: Option[Long] = None, activeSessions: Int = 0,
                         invitationAt: Option[Long] = None, history: List[UserAudit] = Nil, platformAdmin: Boolean = false) derives RW
