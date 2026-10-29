package logicov.facts

import logicov.analysis.{Facility, FacilityAction}
import logicov.source.{Source, Sourced}

/**
 * Reads the facility being underwritten from the discovery-call facts.
 */
object FacilityTerms {
  private val Date = """(\d{1,2}) ([A-Z][a-z]+) (\d{4})""".r
  private val TargetYear = """target maturity (\d{4})""".r
  private val Agent = """([A-Z][^;]*?) administrative agent""".r

  def parse(call: CallFacts): Facility = Facility(kind(call), action(call("Request")), maturity(call), agent(call))

  def action(request: String): FacilityAction = request.toLowerCase match {
    case r if r.startsWith("hold") => FacilityAction.Hold
    case r if r.contains("amend") || r.contains("extend") => FacilityAction.AmendAndExtend
    case r if r.contains("increase") => FacilityAction.Increase
    case _ => FacilityAction.New
  }

  /** "21 May 2031" becomes "May 21, 2031". */
  def american(text: String): String = Date.replaceAllIn(text, m => s"${m.group(2)} ${m.group(1)}, ${m.group(3)}")

  private def kind(call: CallFacts): String = s"${call("Ranking / security").split(";|,").head.trim.toLowerCase} RCF"

  private def maturity(call: CallFacts): Sourced[String] = call.fields.get("Requested tenor") match {
    case Some(requested) =>
      val target = TargetYear.findFirstMatchIn(requested).map(_.group(1)).getOrElse(requested)
      Sourced(s"target $target (per management; current maturity ${american(call("Current maturity"))})", Source.CallScript("Requested tenor"))
    case None =>
      val tenor = call("Tenor")
      Sourced(Date.findFirstIn(tenor).map(american).getOrElse(tenor), Source.CallScript("Tenor"))
  }

  private def agent(call: CallFacts): Sourced[String] = call.fields.get("Administrative agent") match {
    case Some(agent) => Sourced(agent, Source.CallScript("Administrative agent"))
    case None =>
      val facility = call("Existing facility")
      val name = Agent.findFirstMatchIn(facility).map(_.group(1).trim)
        .getOrElse(throw new IllegalArgumentException(s"No administrative agent in '$facility'"))
      Sourced(name, Source.CallScript("Existing facility"))
  }
}
