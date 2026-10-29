package logicov.edgar

/**
 * Reads a full-year figure from an earnings-release table: the first row whose label matches, whose last two
 * numeric cells are the current and prior fiscal year (percent-change cells are skipped).
 */
object EarningsRelease {
  private val Row = """(?is)<tr[^>]*>(.*?)</tr>""".r
  private val Cell = """(?is)<t[dh][^>]*>(.*?)</t[dh]>""".r
  private val Tag = """<[^>]+>""".r
  private val NumericEntity = """&#(x?)([0-9a-fA-F]+);""".r
  private val Number = """\(?-?[\d,]+(?:\.\d+)?\)?""".r

  def annual(html: String, label: String, fiscalYear: Int): Map[Int, BigDecimal] = {
    val wanted = normalize(label)
    Row.findAllMatchIn(html).map(m => cells(m.group(1))).find(_.headOption.exists(c => normalize(c) == wanted)) match {
      case Some(row) =>
        val values = row.indices.drop(1).collect {
          case i if Number.matches(row(i)) && !row.lift(i + 1).exists(_.startsWith("%")) => number(row(i))
        }.toList
        if (values.size < 2) throw new IllegalArgumentException(s"Release row '$label' has no full-year columns: ${row.mkString(" | ")}")
        Map(fiscalYear -> values(values.size - 2), (fiscalYear - 1) -> values.last)
      case None => throw new NoSuchElementException(s"No '$label' row in the earnings release")
    }
  }

  private def cells(row: String): List[String] = Cell.findAllMatchIn(row).map(c => text(c.group(1)))
    .filter(c => c.nonEmpty && c != "$" && c != ")").toList

  private def text(html: String): String = NumericEntity.replaceAllIn(Tag.replaceAllIn(html, ""), m =>
    Integer.parseInt(m.group(2), if (m.group(1).isEmpty) 10 else 16).toChar.toString
  ).replace("&nbsp;", " ").replace("&amp;", "&").replace(' ', ' ').replaceAll("\\s+", " ").trim

  private def normalize(label: String): String = label.toLowerCase.replaceAll("[^a-z0-9]+", " ").trim

  private def number(cell: String): BigDecimal = {
    val digits = BigDecimal(cell.replaceAll("[(),]", ""))
    if (cell.startsWith("(")) -digits else digits
  }
}
