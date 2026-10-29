package logicov.model

import fabric.rw.RW

import java.time.LocalDate

object JsonFormats {
  given localDateRW: RW[LocalDate] = RW.string(_.toString, LocalDate.parse)
}
