package logicov.ingest

import logicov.grid.LineItem

import java.util.Locale

object StatementLabels {
  def normalize(value: String): String = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "")
  private val aliases: Map[String, LineItem] = Map(
    "net sales" -> LineItem.Revenue, "sales" -> LineItem.Revenue, "total revenue" -> LineItem.Revenue,
    "cash and cash equivalents" -> LineItem.Cash, "cash and cash equivalents at end of year" -> LineItem.Cash,
    "total assets" -> LineItem.TotalAssets, "total liabilities" -> LineItem.TotalLiabilities,
    "total stockholders equity" -> LineItem.TotalEquity, "total shareholders equity" -> LineItem.TotalEquity,
    "total equity" -> LineItem.TotalEquity, "current assets" -> LineItem.CurrentAssets,
    "total current assets" -> LineItem.CurrentAssets, "total current liabilities" -> LineItem.CurrentLiabilities,
    "cost of sales" -> LineItem.CostOfRevenue, "cost of goods sold" -> LineItem.CostOfRevenue,
    "operating income" -> LineItem.OperatingIncome, "income before income taxes" -> LineItem.PretaxIncome,
    "income tax expense" -> LineItem.IncomeTax, "interest expense" -> LineItem.InterestExpense,
    "net cash provided by operating activities" -> LineItem.OperatingCashFlow,
    "net cash used in operating activities" -> LineItem.OperatingCashFlow,
    "capital expenditures" -> LineItem.CashCapex, "cash dividends paid" -> LineItem.DividendsPaid,
    "current maturities of long term debt" -> LineItem.CurrentDebt,
    "long term debt net of current portion" -> LineItem.LongTermDebtCarrying
  ).map((label, item) => normalize(label) -> item)
  private val names = LineItem.values.map(item => normalize(item.toString) -> item).toMap

  def find(label: String, mappings: Map[String, LineItem]): Option[LineItem] =
    mappings.get(label).orElse(mappings.collectFirst { case (key, value) if normalize(key) == normalize(label) => value })
      .orElse(names.get(normalize(label))).orElse(aliases.get(normalize(label)))
}
