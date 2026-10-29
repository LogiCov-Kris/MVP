package logicov.edgar

import logicov.source.{Source, Sourced}

/**
 * Reads the weighted-average remaining contract life stated in a 10-K's business description.
 */
object ContractLife {
  private val Statement = """(?i)weighted average (?:remaining )?contract life of ([^.]{1,200}?) (?:was|is) approximately (\w+) years""".r
  private val Entity = """&#(\d+);""".r
  private val Words = List("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve")

  def find(document: String, form: String, accession: String): Option[Sourced[BigDecimal]] = {
    val text = Entity.replaceAllIn(document.replaceAll("<[^>]+>", " "), m => Integer.parseInt(m.group(1)).toChar.toString)
      .replace("&nbsp;", " ").replace('\u00a0', ' ').replaceAll("\\s+", " ")
    Statement.findFirstMatchIn(text).flatMap { m =>
      val years = m.group(2).toLowerCase
      val value = years.toIntOption.orElse(Some(Words.indexOf(years) + 1).filter(_ > 0))
      value.map(v => Sourced(BigDecimal(v), Source.Exhibit(form, accession, "business description",
        s"weighted average remaining contract life of ${m.group(1).trim}, about $v years")))
    }
  }
}
