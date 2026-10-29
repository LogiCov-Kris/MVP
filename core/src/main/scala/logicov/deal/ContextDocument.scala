package logicov.deal

import fabric.rw.*

/** A retained source document, not a generated memo or an analyst invention. */
case class ContextDocument(name: String, text: String) derives RW
