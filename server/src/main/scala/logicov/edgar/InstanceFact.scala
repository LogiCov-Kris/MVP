package logicov.edgar

import java.time.LocalDate

case class InstanceFact(concept: String,
                        start: Option[LocalDate],
                        end: LocalDate,
                        dimensions: Map[String, String],
                        unit: Option[String],
                        text: String) {
  def amount: BigDecimal = BigDecimal(text)

  def isUsd: Boolean = unit.contains("USD")
}
