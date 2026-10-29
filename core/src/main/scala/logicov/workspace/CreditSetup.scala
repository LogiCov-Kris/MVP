package logicov.workspace

import logicov.analysis.*
import logicov.deal.{AnalystInputs, DealRecord, EbitdaBasis}
import logicov.grid.{LineItem, Period}
import logicov.source.{Source, Sourced}

object CreditSetup {
  def build(deal: WorkspaceDeal, request: CreditSetupRequest): DealRecord = {
    require(deal.record.isEmpty, "This deal already has a credit profile")
    require(request.identifier.matches("[A-Za-z0-9-]{1,20}"), "Use a borrower reference of 1 to 20 letters, digits or hyphens")
    require(request.year >= 1902 && request.year <= 2100, "Invalid fiscal year")
    require(List(request.facilityKind, request.maturity, request.agent, request.concentrationBasis, request.supportBasis, request.citation, request.reason)
      .forall(v => v.trim.nonEmpty && v.length <= 2000), "Complete the source reference, terms and judgment explanations")
    require(request.commitment > 0 && request.availability >= 0 && request.availability <= request.commitment, "Check the commitment and undrawn availability")
    require(request.covenantThreshold > 0 && (request.covenantKind != CovenantKind.MaxDebtToCapitalization || request.covenantThreshold < 1), "Check the covenant threshold; capitalization uses a fraction such as 0.60")
    require(request.annualEbitda.size == 3 && request.annualEbitda.forall(_ > 0), "Provide positive EBITDA for each of the three fiscal years")
    require(List(request.largestShare, request.priceLinkedShare).forall(v => v >= 0 && v <= 1), "Concentration shares must be between 0% and 100%")
    val document = deal.documents.find(d => d.id == request.documentId && !d.name.startsWith("generated-"))
      .getOrElse(throw new IllegalArgumentException("Choose a retained source document"))
    val source = Source.Upload(document.name, document.sha256, request.citation, request.reason)
    val years = List(request.year - 2, request.year - 1, request.year)
    val yearEnd = Period.yearEnd(request.year)
    val required = years.flatMap(y => List(LineItem.OperatingCashFlow, LineItem.CashCapex).map(_ -> Period.fiscalYear(y))) ++
      List(LineItem.InterestExpense, LineItem.Revenue, LineItem.DividendsPaid).map(_ -> Period.fiscalYear(request.year)) ++
      List(LineItem.DebtPrincipal, LineItem.CurrentDebt, LineItem.LongTermDebtCarrying, LineItem.Cash, LineItem.TotalEquity).map(_ -> yearEnd)
    val missing = required.filter((item, period) => deal.grid.get(item, period).isEmpty)
    require(missing.isEmpty, "The grid needs these cited values before credit analysis: " + missing.map((item, period) => s"$item (${period.label})").mkString(", "))
    require(deal.grid(LineItem.InterestExpense, Period.fiscalYear(request.year)) > 0 && KeyRatios.grossDebt(deal.grid, yearEnd) > 0,
      "The current scorecard requires positive interest expense and gross debt; zero-debt or zero-interest cases require a separate methodology review")
    require(KeyRatios.grossDebt(deal.grid, yearEnd) + deal.grid(LineItem.TotalEquity, yearEnd) > 0, "Debt plus equity must be positive for capitalization analysis")
    val profile = CreditProfile(
      Facility(request.facilityKind, request.action, Sourced(request.maturity, source), Sourced(request.agent, source)),
      Sourced(request.commitment, source), Sourced(request.availability, source),
      Covenant(request.covenantKind, Sourced(request.covenantThreshold, source)), None,
      years.zip(request.annualEbitda).map((year, amount) => AnnualFigure(year, amount, source)), Nil,
      Sourced(request.largestShare, source), Sourced(request.priceLinkedShare, source), request.concentrationBasis,
      None, None, request.customerOverTenPercent, request.support, Nil, None, Nil, Nil, None)
    val identifier = request.identifier.toUpperCase
    val inputs = AnalystInputs(identifier, 0, request.support, request.supportBasis, Nil, None,
      request.customerOverTenPercent, None, EbitdaBasis(None, Some("Analyst-confirmed EBITDA"), None),
      false, None, Nil, None, Nil)
    val record = DealRecord(deal.grid.entity, identifier, 0, SpreadPeriods(years, Period.fiscalYear(request.year), yearEnd),
      deal.grid, profile, Map.empty, Nil, inputs)
    CreditAnalysis.run(record)
    record
  }
}
