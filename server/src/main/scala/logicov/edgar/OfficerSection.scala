package logicov.edgar

import java.util.regex.{Matcher, Pattern}

/**
 * Locates the executive-officer listing in a 10-K or proxy statement: the "Executive Officers" heading that is followed
 * closely by names with ages, as a table or as biographies.
 */
object OfficerSection {
  private val Heading = """(?i)executive officers""".r
  private val Listing = """(?i)\bName\s+(Current Title\s+)?Age\b|\(age \d{2}\)|, age \d{2}\b""".r
  private val Lookahead = 2500
  private val Length = 15000

  def find(document: String): Option[String] = {
    val text = plain(document)
    Heading.findAllMatchIn(text).map(_.start).find(start => Listing.findFirstIn(text.slice(start, start + Lookahead)).isDefined)
      .map(start => text.slice(start, start + Length))
  }

  /** Director-officers often have biographies in the proxy's director section instead of its officer table. */
  def biography(document: String, name: String): Option[String] = {
    val text = plain(document)
    val heading = (Pattern.quote(name) + """\s+Director since \d{4}\s+""").r
    heading.findFirstMatchIn(text).flatMap { found =>
      val remainder = text.substring(found.end)
      val sentence = """^(?:(?:Mr|Ms|Mrs|Dr)\.|[^.])+\.""".r
      sentence.findFirstIn(remainder).map { first =>
        val rest = remainder.substring(first.length).trim
        val second = Option.when(rest.startsWith("He ") || rest.startsWith("She "))(rest)
          .flatMap(sentence.findFirstIn)
        first + second.fold("")(" " + _)
      }
    }
  }

  def plain(document: String): String = {
    val numeric = """&#(x[0-9a-fA-F]+|[0-9]+);""".r.replaceAllIn(document.replaceAll("<[^>]+>", " "), m => {
      val number = m.group(1)
      val code = if (number.startsWith("x")) Integer.parseInt(number.drop(1), 16) else Integer.parseInt(number)
      Matcher.quoteReplacement(new String(Character.toChars(code)))
    })
    numeric.replace("&rsquo;", "’").replace("&lsquo;", "‘").replace("&rdquo;", "”").replace("&ldquo;", "“")
      .replace("&nbsp;", " ").replace("&amp;", "&").replace("&apos;", "'").replace("&quot;", "\"")
      .replace(' ', ' ').replaceAll("\\s+", " ")
  }
}
