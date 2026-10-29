package logicov.edgar

import logicov.analysis.AgencyRating
import logicov.memo.Format
import logicov.source.Source

/** Reads explicitly ordered agency/rating tuples from retained offering term sheets. */
object OfferingRatings {
  private val Rows = "(?is)<tr\\b[^>]*>.*?</tr>".r
  private val RatingThenHouse = """Expected Ratings\s*\(?\d*\)?\s*:?\s*(Baa[123])\s*\((stable|negative|positive)\)\s*/\s*(BBB[+-]?)\s*\((stable|negative|positive)\)\s*\(Moody['’]s\s*/\s*S&P\)""".r
  private val HouseThenRating = """Expected Ratings\s*\(Moody['’]s\s*/\s*S&P\s*/\s*Fitch\)\s*\*?\s*:\s*(Baa[123])\s*/\s*(BBB[+-]?)\s*/\s*(BBB[+-]?)\s*\((negative|stable|positive)\s*/\s*(negative|stable|positive)\s*/\s*(negative|stable|positive)\)""".r

  def extract(document: String, filing: PackFiling): List[AgencyRating] = {
    require(filing.form == "FWP", "Offering ratings require a filed pricing term sheet")
    val rows = Rows.findAllIn(document).map(OfficerSection.plain).filter(_.contains("Expected Ratings")).toList
    val found = rows.flatMap { row =>
      RatingThenHouse.findFirstMatchIn(row).map(m => List(
        ("Moody's", m.group(1), m.group(2)), ("S&P", m.group(3), m.group(4))))
        .orElse(HouseThenRating.findFirstMatchIn(row).map(m => List(
          ("Moody's", m.group(1), m.group(4)), ("S&P", m.group(2), m.group(5)), ("Fitch", m.group(3), m.group(6)))))
        .toList.flatten
    }.distinct
    require(found.nonEmpty && found.map(_._1).distinct.size == found.size,
      s"Unrecognized or conflicting offering ratings in ${filing.file}")
    val basis = if (OfficerSection.plain(document).contains("Parent Guarantor:")) "expected parent-guaranteed notes ratings" else "expected senior unsecured notes ratings"
    val source = Source.Exhibit(filing.form, filing.accession, "expected offering ratings", "pricing term sheet; " + filing.url)
    found.map { (house, rating, outlook) =>
      AgencyRating(house, rating, outlook.capitalize, s"$basis, ${Format.date(filing.filed)}", source)
    }
  }
}
