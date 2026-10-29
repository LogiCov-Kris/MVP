package logicov.memo

import scala.util.matching.Regex.Match

object NarrativeCheck {
  private val Figure = """\$?(\d[\d,]*(?:\.\d+)?)\s*(billion|million|x|%|bps)?""".r

  def unverified(text: String, facts: String): List[String] = {
    val known = Figure.findAllMatchIn(facts).flatMap(readings).toSet
    Figure.findAllMatchIn(text)
      .filterNot(m => isSmallInteger(m.group(1)) || readings(m).exists(known.contains))
      .map(_.matched.trim)
      .toList
      .distinct
  }

  private def readings(m: Match): Set[String] = {
    val value = BigDecimal(m.group(1).replace(",", ""))
    val inMillions = if (m.group(2) == "billion") Set(value * 1000) else Set.empty[BigDecimal]
    (inMillions + value).map(_.bigDecimal.stripTrailingZeros.toPlainString)
  }

  private def isSmallInteger(figure: String): Boolean = figure.forall(_.isDigit) && figure.toInt <= 12
}
