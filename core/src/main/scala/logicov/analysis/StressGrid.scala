package logicov.analysis

case class StressGrid(ebitda: BigDecimal,
                      netDebt: BigDecimal,
                      interest: BigDecimal,
                      operatingCashFlow: BigDecimal,
                      capex: BigDecimal,
                      dividends: BigDecimal,
                      leverageCovenant: Option[BigDecimal],
                      capitalizationCovenant: Option[CapitalizationTest],
                      revenue: BigDecimal = 0) {
  def run(scenario: StressScenario): StressRow = {
    val debt = netDebt + scenario.addedDebt
    val shocked = leverageCovenant.filter(_ => scenario.reverseToCovenant) match {
      case Some(covenant) => debt / covenant
      case None => scenario.revenueChange match {
        case Some(change) => ebitda + revenue * change * (1 - scenario.costFlex.getOrElse(BigDecimal(0)))
        case None => ebitda * (1 + scenario.ebitdaChange) + scenario.ebitdaDelta
      }
    }
    val cashFlow = operatingCashFlow - (ebitda - shocked)
    // Leverage and coverage are not meaningful once EBITDA is zero or negative; a loss case needs its own analysis.
    val leverage = Option.when(shocked > 0)(debt / shocked)
    StressRow(
      scenario = scenario,
      ebitda = shocked,
      netDebt = debt,
      leverage = leverage,
      covenantHeadroom = leverageCovenant match {
        case Some(threshold) => leverage.map(threshold - _)
        case None => capitalizationCovenant.map(_.headroom(scenario.addedDebt, scenario.equityChange))
      },
      interestCoverage = Option.when(shocked > 0)(shocked / interest),
      dividendCover = cashFlow / dividends,
      cashAfterCapexAndDividends = cashFlow - capex - dividends
    )
  }
}
