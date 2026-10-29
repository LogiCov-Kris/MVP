package logicov.facts

object Amounts {
  private val Money = """\$([\d,]+(?:\.\d+)?)\s*(million|billion)""".r
  private val Percent = """(\d+(?:\.\d+)?)\s*%""".r
  private val Multiple = """(\d+(?:\.\d+)?)\s*x""".r

  def money(text: String): BigDecimal = Money.findFirstMatchIn(text) match {
    case Some(m) =>
      val amount = BigDecimal(m.group(1).replace(",", ""))
      if (m.group(2) == "billion") amount * 1000 else amount
    case None => throw new IllegalArgumentException(s"No dollar amount in '$text'")
  }

  def percent(text: String): BigDecimal = Percent.findFirstMatchIn(text) match {
    case Some(m) => BigDecimal(m.group(1)) / 100
    case None => throw new IllegalArgumentException(s"No percentage in '$text'")
  }

  def multiple(text: String): BigDecimal = Multiple.findFirstMatchIn(text) match {
    case Some(m) => BigDecimal(m.group(1))
    case None => throw new IllegalArgumentException(s"No multiple in '$text'")
  }

  def clause(text: String, marker: String): String =
    if (marker.isEmpty) text
    else text.split(";").find(_.contains(marker)).getOrElse(throw new IllegalArgumentException(s"No clause with '$marker' in '$text'"))
}
