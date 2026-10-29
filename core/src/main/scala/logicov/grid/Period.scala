package logicov.grid

import fabric.rw.*
import logicov.model.JsonFormats.given

import java.time.LocalDate

case class Period(label: String, start: Option[LocalDate], end: LocalDate) derives RW {
  def isInstant: Boolean = start.isEmpty
}

object Period {
  def fiscalYear(year: Int): Period = Period(s"FY$year", Some(LocalDate.of(year, 1, 1)), LocalDate.of(year, 12, 31))

  def yearEnd(year: Int): Period = Period(s"Dec 31, $year", None, LocalDate.of(year, 12, 31))

  def interim(label: String, start: LocalDate, end: LocalDate): Period = Period(label, Some(start), end)

  def balance(label: String, date: LocalDate): Period = Period(label, None, date)
}
