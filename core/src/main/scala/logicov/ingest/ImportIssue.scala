package logicov.ingest

import fabric.rw.*

case class ImportIssue(kind: ImportIssueKind, location: String, message: String) derives RW
