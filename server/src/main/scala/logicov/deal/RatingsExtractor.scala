package logicov.deal

import fabric.io.JsonParser
import logicov.ai.LogiCovSigil
import logicov.analysis.AgencyRating
import logicov.edgar.RatingsDisclosure
import logicov.source.Source
import rapid.Task

/**
 * Reads the long-term ratings a company discloses for itself. The model reads the located passages; code accepts a
 * rating only when the symbol appears verbatim within a short distance of that agency's name.
 */
object RatingsExtractor {
  private val Houses = Map("S&P" -> List("S&P", "Standard & Poor"), "Moody's" -> List("Moody"), "Fitch" -> List("Fitch"))
  private val Reach = 150

  private val System =
    """You read excerpts of a company's annual report and list the company's own senior unsecured or long-term debt ratings.
      |Return only a JSON array, no prose. Each element: {"house": "S&P" | "Moody's" | "Fitch", "rating": ..., "outlook": ... or null}.
      |Copy the rating symbol exactly as written. Ignore short-term, commercial paper and subsidiary ratings, and index names such
      |as the S&P 500. If the excerpts state no such rating, return [].""".stripMargin

  def extract(passages: List[String], form: String, accession: String, asOf: String): Task[List[AgencyRating]] =
    if (passages.isEmpty) Task.pure(Nil)
    else LogiCovSigil.complete(System, passages.mkString("\n\n---\n\n")).map { reply =>
      val json = JsonParser(reply.substring(reply.indexOf('['), reply.lastIndexOf(']') + 1))
      val source = Source.Exhibit(form, accession, "credit ratings", "as disclosed by the company")
      json.asVector.toList.map { r =>
        AgencyRating(r("house").asString, r("rating").asString, r.get("outlook").filterNot(_.isNull).fold("not stated")(_.asString.capitalize),
          asOf, source)
      }.filter(verified(passages, _)).distinctBy(_.house)
    }

  /**
   * The rating must sit beside an occurrence of the agency's name with no other agency named in between: the first symbol
   * after the name ("S&P: A-", a ratings table) or the last one before it ("Baa2 by Moody's").
   */
  def verified(passages: List[String], rating: AgencyRating): Boolean =
    RatingsDisclosure.Symbol.matches(rating.rating) && Houses.get(rating.house).exists { names =>
      passages.exists { passage =>
        val agencies = Houses.values.flatten.toList.flatMap(n => occurrences(passage, n).map(_ -> n))
        names.exists(name => occurrences(passage, name).exists { at =>
          val next = agencies.collect { case (o, _) if o > at => o }.minOption.getOrElse(passage.length)
          val previous = agencies.collect { case (o, n) if o + n.length <= at => o + n.length }.maxOption.getOrElse(0)
          val after = RatingsDisclosure.Symbol.findFirstMatchIn(passage.slice(at + name.length, math.min(next, at + Reach))).map(_.matched)
          val before = RatingsDisclosure.Symbol.findAllMatchIn(passage.slice(math.max(previous, at - Reach), at)).toList.lastOption.map(_.matched)
          after.contains(rating.rating) || before.contains(rating.rating)
        })
      }
    }

  private def occurrences(text: String, name: String): List[Int] =
    Iterator.iterate(text.indexOf(name))(i => text.indexOf(name, i + 1)).takeWhile(_ >= 0).toList
}
