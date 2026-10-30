package logicov.model

import fabric.rw.*

case class UserAudit(action: UserAction, actor: String, at: Long, detail: String) derives RW
