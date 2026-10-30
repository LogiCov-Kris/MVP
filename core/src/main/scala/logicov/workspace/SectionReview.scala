package logicov.workspace

import fabric.rw.*

case class SectionReview(section: String, actor: String, at: String) derives RW
