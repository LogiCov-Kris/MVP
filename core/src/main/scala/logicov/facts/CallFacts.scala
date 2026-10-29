package logicov.facts

import logicov.source.{Source, Sourced}

case class CallFacts(fields: Map[String, String]) {
  def apply(field: String): String = fields.getOrElse(field, throw new NoSuchElementException(s"Call script has no '$field' field"))

  def money(field: String, marker: String = ""): Sourced[BigDecimal] =
    Sourced(Amounts.money(Amounts.clause(apply(field), marker)), Source.CallScript(field))

  def percent(field: String, marker: String): Sourced[BigDecimal] =
    Sourced(Amounts.percent(Amounts.clause(apply(field), marker)), Source.CallScript(field))

  def multiple(field: String): Sourced[BigDecimal] = Sourced(Amounts.multiple(apply(field)), Source.CallScript(field))
}

object CallFacts {
  private val TableHeading = "Facts the tool should extract"
  private val Row = """^\|\s*(.+?)\s*\|\s*(.+?)\s*\|\s*$""".r

  def parse(markdown: String): CallFacts = {
    val afterHeading = markdown.linesIterator.dropWhile(line => !line.contains(TableHeading)).drop(1)
    val table = afterHeading.dropWhile(line => !line.trim.startsWith("|")).takeWhile(_.trim.startsWith("|")).toList
    val rows = table.collect {
      case Row(field, value) if field != "Field" && !field.startsWith("---") => field -> value
    }
    if (rows.isEmpty) throw new IllegalArgumentException(s"No '$TableHeading' table found in the call script")
    CallFacts(rows.toMap)
  }
}
