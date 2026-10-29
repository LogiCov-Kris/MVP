package logicov.facts

import logicov.analysis.AgencyRating
import logicov.source.Source

/**
 * Reads "S&P BBB / Negative (18 Feb 2026); Moody's Baa2 / Stable; Fitch BBB" into one rating per house.
 */
object AgencyRatingsText {
  private val NotProvided = "not stated"
  private val Clause = """^(S&P|Moody[’']s|Fitch)\s+(Aaa|[A-C][a-z]{0,2}\d|[A-C]{1,3}[+-]?)(?:\s*/\s*([A-Za-z]+))?(?:\s*\(([^)]*)\))?""".r

  def parse(text: String, source: Source): List[AgencyRating] = text.split(";").toList.map(_.trim).collect {
    case Clause(house, rating, outlook, asOf) =>
      AgencyRating(house.replace('’', '\''), rating, Option(outlook).getOrElse(NotProvided),
        Option(asOf).map(FacilityTerms.american).getOrElse(NotProvided), source)
  }
}
