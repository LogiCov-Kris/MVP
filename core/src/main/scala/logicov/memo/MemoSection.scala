package logicov.memo

import fabric.rw.*

case class MemoSection(title: String, blocks: List[MemoBlock]) derives RW
