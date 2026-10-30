package logicov.model

import fabric.rw.*

case class ManagedWorkspace(id: String, name: String, active: Boolean, revision: Long,
                            users: Int, activeUsers: Int, administrators: Int,
                            lastSignIn: Option[Long], history: List[WorkspaceAudit]) derives RW
