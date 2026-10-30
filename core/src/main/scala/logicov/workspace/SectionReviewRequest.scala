package logicov.workspace

import fabric.rw.*

case class SectionReviewRequest(revision: Long, section: String, reviewed: Boolean = true) derives RW
