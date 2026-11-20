package logicov.publiccompany

import logicov.workspace.{ProfileDisclosure, ProfileEntry, ProfileField, WorkspaceDocument}

import java.util.regex.Matcher

object CreditDisclosures {
  private val Tags = "(?is)<(?:script|style|ix:header)\\b[^>]*>.*?</(?:script|style|ix:header)>|<[^>]+>".r
  private val Entity = "&#(x?)([0-9a-fA-F]+);".r
  private val Agent = "(?i)([A-Z][A-Za-z .,&-]{2,65}), as administrative agent".r
  private val Commitment = "(?i)aggregate commitments of(?: up to)? \\$\\s*([0-9.,]+)\\s*(billion|million)".r
  private val Availability = "(?i)([0-9.,]+)\\s*(billion|million) available for borrowings".r
  private val Capitalization = "(?i)(?:indebtedness|debt) to capitalization.{0,500}?not to exceed ([0-9.]+)\\s*%".r
  private val Maturity = "(?i)maturity date of ([A-Z][a-z]+ \\d{1,2}, \\d{4})".r
  private val Extension = "(?i)obtained consent.{0,180}?extend the maturity.{0,160}?to ([A-Z][a-z]+ \\d{1,2}, \\d{4})".r

  def read(document: WorkspaceDocument, html: String): List[ProfileDisclosure] = {
    val text = Entity.replaceAllIn(Tags.replaceAllIn(html, " "), m => {
      val code = Integer.parseInt(m.group(2), if (m.group(1).isEmpty) 10 else 16)
      if (Character.isValidCodePoint(code)) Matcher.quoteReplacement(new String(Character.toChars(code))) else " "
    }).replace("&nbsp;", " ").replace("&amp;", "&").replace('\u00a0', ' ').replaceAll("\\s+", " ")
    Agent.findAllMatchIn(text).take(8).flatMap { agent =>
      val before = text.substring(math.max(0, agent.start - 900), agent.start)
      val after = text.substring(agent.end, math.min(text.length, agent.end + 3500))
      val nextAgent = Agent.findFirstMatchIn(after).map(_.start).getOrElse(after.length)
      val excerpt = (before + agent.matched + after.take(nextAgent)).take(4500)
      val commitments = Commitment.findAllMatchIn(excerpt).map(m => BigDecimal(m.group(1).replace(",", "")) * (if (m.group(2).equalsIgnoreCase("billion")) 1000 else 1)).toList.distinct
      if (commitments.size != 1 || !excerpt.toLowerCase.contains("credit")) None else {
        def unique(field: ProfileField, values: List[String]): List[ProfileEntry] = values.distinct match {
          case value :: Nil => List(ProfileEntry(field, value))
          case _ => Nil
        }
        val availability = Availability.findAllMatchIn(excerpt).map(m => (BigDecimal(m.group(1).replace(",", "")) * (if (m.group(2).equalsIgnoreCase("billion")) 1000 else 1)).bigDecimal.stripTrailingZeros.toPlainString).toList
        val covenant = Capitalization.findAllMatchIn(excerpt).map(m => (BigDecimal(m.group(1)) / 100).toString).toList.distinct
        val extensions = Extension.findAllMatchIn(excerpt).map(_.group(1)).toList
        val maturities = if (extensions.nonEmpty) extensions else Maturity.findAllMatchIn(excerpt).map(_.group(1)).toList
        val entries = List(ProfileEntry(ProfileField.Commitment, commitments.head.bigDecimal.stripTrailingZeros.toPlainString),
          ProfileEntry(ProfileField.Agent, agent.group(1).stripPrefix("and ").trim), ProfileEntry(ProfileField.FacilityKind, "Credit facility")) ++
          unique(ProfileField.Availability, availability) ++ unique(ProfileField.Maturity, maturities) ++
          (if (covenant.size == 1) List(ProfileEntry(ProfileField.Covenant, "1"), ProfileEntry(ProfileField.Threshold, covenant.head)) else Nil)
        Some(ProfileDisclosure(document.id, document.name, excerpt, entries))
      }
    }.toList.distinct.take(3)
  }
}
