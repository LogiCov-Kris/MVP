package logicov.workspace

import fabric.rw.*

case class DocumentRequest(revision: Long, filename: String, data: String) derives RW
