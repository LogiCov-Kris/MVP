package logicov.edgar

import fabric.Json

import java.time.LocalDate

case class CompanyFacts(cik: Long, entityName: String, facts: List[XbrlFact]) {
  def instant(tag: String, date: LocalDate): Option[XbrlFact] =
    latest(facts.filter(f => f.tag == tag && f.start.isEmpty && f.end == date))

  def duration(tag: String, start: LocalDate, end: LocalDate): Option[XbrlFact] =
    latest(facts.filter(f => f.tag == tag && f.start.contains(start) && f.end == end))

  private def latest(candidates: List[XbrlFact]): Option[XbrlFact] =
    candidates.filter(f => CompanyFacts.PeriodicForms.contains(f.form)).maxByOption(_.filed)
}

object CompanyFacts {
  private val Taxonomy = "us-gaap"
  private val PeriodicForms = Set("10-K", "10-K/A", "10-Q", "10-Q/A")
  private val Unit = "USD"

  def parse(json: Json): CompanyFacts = {
    val facts = for {
      (tag, concept) <- json("facts")(Taxonomy).asObj.value.toList
      units <- concept("units").get(Unit).toList
      entry <- units.asArr.value.toList
    } yield XbrlFact(
      tag = tag,
      start = entry.get("start").map(s => LocalDate.parse(s.asString)),
      end = LocalDate.parse(entry("end").asString),
      value = entry("val").asBigDecimal,
      form = entry("form").asString,
      accession = entry("accn").asString,
      filed = LocalDate.parse(entry("filed").asString)
    )
    CompanyFacts(json("cik").asLong, json("entityName").asString, facts)
  }
}
