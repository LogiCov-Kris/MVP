package logicov.ingest

import fabric.rw.*

enum ImportIssueKind derives RW {
  case UnknownLabel, MissingValue, InvalidValue, Formula, Duplicate, Reconciliation, Empty, Header
}
