package logicov.ingest

import fabric.rw.*

case class ImportRequest(filename: String, data: String, options: ImportOptions) derives RW
