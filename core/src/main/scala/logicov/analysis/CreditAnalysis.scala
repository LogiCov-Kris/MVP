package logicov.analysis

import logicov.deal.DealRecord
import logicov.grid.{LineItem, Period}
import logicov.rating.{RatingEngine, RatingInputs, RatingResult}

case class CreditAnalysis(record: DealRecord,
                          ratios: KeyRatios,
                          rating: RatingResult,
                          covenant: CovenantHeadroom,
                          stress: Option[StressGrid])

object CreditAnalysis {
  def run(record: DealRecord): CreditAnalysis = {
    val grid = record.grid
    val profile = record.profile
    val periods = record.periods
    val ratios = KeyRatios.compute(grid, profile, periods)
    val inputs = RatingInputs(
      ladder = profile.ladder,
      ebitda = ratios.ebitda,
      scaleEbitda = ratios.scaleEbitda,
      netLeverage = ratios.netLeverage,
      fcfToDebt = ratios.fcfToDebt,
      interestCoverage = ratios.interestCoverage,
      liquidityCover = ratios.liquidityCover,
      netCash = ratios.netDebt < 0,
      largestShare = profile.largestShare.value,
      concentrationBasis = profile.concentrationBasis,
      commodityModifier = profile.commodityModifier,
      priceLinkedShare = profile.priceLinkedShare.value,
      severeTrough = false,
      support = profile.support
    )
    val latest = periods.latest
    val carryingDebt = grid(LineItem.CurrentDebt, latest) + grid(LineItem.LongTermDebtCarrying, latest)
    val leverageCovenant = Some(profile.covenant).filter(_.kind == CovenantKind.MaxNetLeverage).map(_.threshold.value)
    val capitalizationCovenant = Some(profile.covenant).filter(_.kind == CovenantKind.MaxDebtToCapitalization)
      .map(c => CapitalizationTest(c.threshold.value, carryingDebt, grid(LineItem.TotalEquity, latest)))
    val latestNetDebt = KeyRatios.grossDebt(grid, latest) - grid(LineItem.Cash, latest)
    val fy = Period.fiscalYear(periods.lastYear)
    val stress = grid.get(LineItem.DividendsPaid, fy).map { dividends =>
      StressGrid(ratios.ebitda, latestNetDebt, ratios.interest, grid(LineItem.OperatingCashFlow, fy),
        grid(LineItem.CashCapex, fy), dividends.value, leverageCovenant, capitalizationCovenant, grid(LineItem.Revenue, fy))
    }
    CreditAnalysis(
      record = record,
      ratios = ratios,
      rating = RatingEngine.rate(inputs, profile.commitment.value, profile.ratingOverride),
      covenant = CovenantHeadroom.compute(profile.covenant, ratios, carryingDebt, grid(LineItem.TotalEquity, latest)),
      stress = stress
    )
  }
}
