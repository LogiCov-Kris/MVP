package logicov.workspace

import fabric.rw.*

case class JobLease(id: String, expiresAt: Long) derives RW
