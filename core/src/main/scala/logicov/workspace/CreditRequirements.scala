package logicov.workspace

import logicov.grid.{LineItem, Period}

object CreditRequirements {
  def required(year: Int): List[(LineItem, Period)] = {
    val years = List(year - 2, year - 1, year)
    years.flatMap(y => List(LineItem.OperatingCashFlow, LineItem.CashCapex).map(_ -> Period.fiscalYear(y))) ++
      List(LineItem.InterestExpense, LineItem.Revenue, LineItem.DividendsPaid).map(_ -> Period.fiscalYear(year)) ++
      List(LineItem.DebtPrincipal, LineItem.CurrentDebt, LineItem.LongTermDebtCarrying, LineItem.Cash, LineItem.TotalEquity).map(_ -> Period.yearEnd(year))
  }
}
