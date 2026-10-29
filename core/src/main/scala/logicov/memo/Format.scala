package logicov.memo

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import scala.math.BigDecimal.RoundingMode

object Format {
  private val ShortDate = DateTimeFormatter.ofPattern("MMM d, yyyy")

  def millions(value: BigDecimal): String = {
    val formatted = String.format("%,d", value.setScale(0, RoundingMode.HALF_UP).abs.toBigInt.bigInteger)
    if (value < 0) s"($formatted)" else formatted
  }

  def billions(value: BigDecimal): String =
    s"$$${(value / 1000).setScale(3, RoundingMode.HALF_UP).bigDecimal.stripTrailingZeros.toPlainString} billion"

  def multiple(value: BigDecimal): String = s"${value.setScale(2, RoundingMode.HALF_UP)}x"

  def percent(value: BigDecimal, scale: Int = 1): String = s"${(value * 100).setScale(scale, RoundingMode.HALF_UP)}%"

  def date(value: LocalDate): String = value.format(ShortDate)

  def decimal(value: BigDecimal, scale: Int = 2): String = value.setScale(scale, RoundingMode.HALF_UP).toString
}
