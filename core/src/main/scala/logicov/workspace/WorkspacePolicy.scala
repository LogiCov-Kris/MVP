package logicov.workspace

import fabric.rw.*

case class WorkspacePolicy(revision: Long = 0, policy: Option[PolicySet] = None,
                           updatedBy: String = "", updatedAt: String = "", reason: String = "",
                           history: List[WorkspaceEvent] = Nil) derives RW
