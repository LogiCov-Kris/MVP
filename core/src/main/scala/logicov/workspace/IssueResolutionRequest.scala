package logicov.workspace

import fabric.rw.*

case class IssueResolutionRequest(revision: Long, issue: Int, reason: String) derives RW
