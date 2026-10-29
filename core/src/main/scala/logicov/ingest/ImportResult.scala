package logicov.ingest

import fabric.rw.*
import logicov.grid.GoldenGrid

case class ImportResult(grid: GoldenGrid, document: String, sha256: String, issues: List[ImportIssue], rowsRead: Int) derives RW
