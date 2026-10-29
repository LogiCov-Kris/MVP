package logicov.memo

import fabric.rw.*

enum MemoBlock derives RW {
  case Subheading(text: String)
  case Paragraph(text: String)
  case Table(dataset: String)
  case Narrative(author: String, guidance: String)
}
