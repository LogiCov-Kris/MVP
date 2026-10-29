package logicov.rating

import fabric.rw.*
import logicov.model.JsonFormats.given

import java.time.LocalDate

case class RatingOverride(notches: Int, component: String, reason: String, approvedBy: String, date: LocalDate) derives RW {
  if (math.abs(notches) != 1) throw new IllegalArgumentException(s"An override is one notch, not $notches")
}
