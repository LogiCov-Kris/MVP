package logicov.publiccompany

import fabric.rw.*

case class PublicIntakeRequest(cik: Long, requestId: String) derives RW
