package logicov.workspace

import logicov.grid.{GoldenGrid, LineItem, Period}

object ProfilePrefill {
  def annualEbitda(grid: GoldenGrid, year: Int): Option[ProfileSuggestion] = {
    val periods = (year - 2 to year).map(Period.fiscalYear).toList
    val components = periods.map(p => List(LineItem.PretaxIncome, LineItem.InterestExpense, LineItem.Depreciation).flatMap(grid.get(_, p)))
    if (components.forall(_.size == 3)) Some(ProfileSuggestion(ProfileField.Ebitda,
      components.map(_.map(_.value).sum.bigDecimal.stripTrailingZeros.toPlainString).mkString(", "),
      "Calculated EBITDA = pretax income + interest expense + depreciation, depletion and amortization. This is unadjusted EBITDA, not covenant or management-adjusted EBITDA. Confirm the appropriate basis before building the analysis.",
      components.flatten.map(_.source).distinct)) else None
  }

  def suggestions(deal: WorkspaceDeal): List[ProfileSuggestion] = {
    val year = deal.grid.values.filter(_.period.label.startsWith("FY")).map(_.period.end.getYear).maxOption
    deal.publicIssuer.toList.map(i => ProfileSuggestion(ProfileField.Identifier, i.ticker, "SEC issuer identity", Nil)) ++
      year.toList.map(y => ProfileSuggestion(ProfileField.Year, y.toString, "Latest retained annual financial period", Nil)) ++
      year.toList.flatMap(y => annualEbitda(deal.grid, y))
  }
}
