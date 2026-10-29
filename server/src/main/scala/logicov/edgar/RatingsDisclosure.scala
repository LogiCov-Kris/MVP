package logicov.edgar

/**
 * Locates where a 10-K states the company's own credit ratings: an agency's name with a rating symbol close after it,
 * in text that speaks of ratings. Returns the surrounding passages, merged where they overlap.
 */
object RatingsDisclosure {
  private val Agency = """(S&P|Standard & Poor|Moody|Fitch)""".r
  val Symbol = """(?<![A-Za-z])(AAA|AA[+-]|AA|A[+-]|A|BBB[+-]|BBB|BB[+-]|BB|Aaa|Aa[123]|A[123]|Baa[123]|Ba[123])(?![A-Za-z0-9+-])""".r
  private val Reach = 150
  private val Context = 400
  private val MaxPassages = 4

  def passages(document: String): List[String] = {
    val text = OfficerSection.plain(document)
    val starts = Agency.findAllMatchIn(text).map(_.start).filter { at =>
      val near = text.slice(at, at + Reach)
      Symbol.findFirstIn(near.drop(3)).isDefined && text.slice(at - Context, at + Context).toLowerCase.contains("rat")
    }.toList
    val windows = starts.map(at => (math.max(0, at - Context), math.min(text.length, at + Context)))
    val merged = windows.foldLeft(List.empty[(Int, Int)]) {
      case ((s, e) :: rest, (ns, ne)) if ns <= e => (s, math.max(e, ne)) :: rest
      case (acc, window) => window :: acc
    }.reverse
    merged.take(MaxPassages).map((s, e) => text.slice(s, e))
  }
}
