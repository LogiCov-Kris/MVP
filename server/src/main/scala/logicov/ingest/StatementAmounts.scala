package logicov.ingest

import scala.util.Try

object StatementAmounts {
  private val Number = """-?(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\.[0-9]+)?""".r

  def read(value: String): Option[BigDecimal] = {
    val text = value.trim.stripPrefix("$").trim
    val normalized = if (text.startsWith("(") && text.endsWith(")")) "-" + text.drop(1).dropRight(1).trim.stripPrefix("$").trim else text
    Option.when(Number.matches(normalized))(normalized.replace(",", "")).flatMap(s => Try(BigDecimal(s)).toOption)
  }
}
