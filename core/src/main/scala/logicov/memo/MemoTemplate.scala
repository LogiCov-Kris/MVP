package logicov.memo

import fabric.rw.*

case class MemoTemplate(name: String,
                        title: String,
                        header: String,
                        footer: String,
                        coverStrip: List[String],
                        sections: List[MemoSection],
                        disclaimer: String) derives RW
