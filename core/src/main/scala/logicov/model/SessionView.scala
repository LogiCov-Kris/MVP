package logicov.model

import fabric.rw.*

case class SessionView(email: String, tenantId: String, tenantName: String, role: UserRole = UserRole.Analyst) derives RW
