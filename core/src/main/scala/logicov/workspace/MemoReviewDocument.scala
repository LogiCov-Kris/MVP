package logicov.workspace

import fabric.rw.*

case class MemoReviewDocument(sections: List[MemoReviewSection], appendix: String, bindings: Map[String, String], cover: String = "") derives RW
