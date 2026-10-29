package logicov.workspace

import fabric.rw.*

case class WorkspaceDocument(id: String, name: String, contentType: String, sha256: String, bytes: Long, createdAt: String) derives RW
