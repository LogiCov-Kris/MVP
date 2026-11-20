package logicov.workspace

import fabric.rw.*

case class ProfileDraftRequest(revision: Long, entries: List[ProfileEntry]) derives RW
