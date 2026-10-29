package logicov.analysis

import logicov.grid.{GoldenGrid, LineItem, Period}

case class KeyRatios(grossDebt: BigDecimal,
                     cash: BigDecimal,
                     ebitda: BigDecimal,
                     scaleEbitda: BigDecimal,
                     interest: BigDecimal,
                     fcfToDebt: BigDecimal,
                     liquidityCover: BigDecimal,
                     debtToCapitalization: BigDecimal) {
  def netDebt: BigDecimal = grossDebt - cash

  def netLeverage: BigDecimal = netDebt / ebitda

  def interestCoverage: BigDecimal = ebitda / interest
}

object KeyRatios {
  val NoNearMaturities: BigDecimal = BigDecimal(5)

  def compute(grid: GoldenGrid, profile: CreditProfile, periods: SpreadPeriods): KeyRatios = {
    val (ebitda, asOf) = profile.ratingEbitda(periods)
    val latest = periods.latest
    val yearEnd = Period.yearEnd(periods.lastYear)
    val fcf = periods.fiscalYears.map(Period.fiscalYear).map(fy => grid(LineItem.OperatingCashFlow, fy) - grid(LineItem.CashCapex, fy))
    val maturities = grid(LineItem.CurrentDebt, latest)
    val latestCash = grid(LineItem.Cash, latest)
    KeyRatios(
      grossDebt = grossDebt(grid, asOf),
      cash = grid(LineItem.Cash, asOf),
      ebitda = ebitda,
      scaleEbitda = profile.scaleEbitda(periods),
      interest = grid(LineItem.InterestExpense, Period.fiscalYear(periods.lastYear)),
      fcfToDebt = fcf.sum / fcf.size / grossDebt(grid, yearEnd),
      liquidityCover = liquidityCover(latestCash, profile.availability.value, maturities),
      debtToCapitalization = grossDebt(grid, latest) / (grossDebt(grid, latest) + grid(LineItem.TotalEquity, latest))
    )
  }

  def liquidityCover(cash: BigDecimal, availability: BigDecimal, maturities: BigDecimal): BigDecimal =
    if (maturities <= 0) NoNearMaturities else (cash + availability) / maturities

  def grossDebt(grid: GoldenGrid, period: Period): BigDecimal =
    grid(LineItem.DebtPrincipal, period) + grid.orZero(LineItem.FinanceLeases, period)
}
