package logicov.ingest

import fabric.rw.*
import logicov.grid.Period
import logicov.model.JsonFormats.given

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

case class ImportColumn(column: Int, label: String, start: LocalDate, end: LocalDate) derives RW {
  def period(flow: Boolean): Period = {
    val name = if (flow) label else end.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US))
    Period(name, Option.when(flow)(start), end)
  }
}
