package logicov.workspace

import fabric.rw.*

case class PolicyRequest(revision: Long, policy: PolicySet, reason: String) derives RW
