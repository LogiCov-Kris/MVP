package logicov.memo

import logicov.analysis.CreditAnalysis
import logicov.grid.{LineItem, Period}

/** Statement detail is selected from sourced grid values; absent facts are never zero-filled. */
object FinancialTables {
  private val Unavailable = "—"

  def build(a: CreditAnalysis): Map[String, MemoTable] = {
    import LineItem.*
    val record = a.record
    val flows = (record.periods.fiscalYears.map(Period.fiscalYear) :+ record.periods.interim).distinct
    val balances = (record.periods.fiscalYears.map(Period.yearEnd) :+ record.periods.latest).distinct
    def table(title: String, periods: List[Period], lines: List[(String, LineItem)]): MemoTable = {
      val present = lines.filter((_, item) => periods.exists(p => record.grid.get(item, p).isDefined))
      val rows = present.map((label, item) => labelled(label, item, periods) :: periods.map(p =>
        record.grid.get(item, p).fold(Unavailable)(v => Format.millions(v.value))))
      val links = (for {
        ((_, item), r) <- present.zipWithIndex
        (period, c) <- periods.zipWithIndex
        value <- record.grid.get(item, period)
      } yield (r, c + 1) -> s"${record.ticker.toLowerCase}-audit.html#${SourceAnchor.id(record.ticker, item, period)}").toMap
      MemoTable(title, "" :: periods.map(_.label), rows,
        Some(s"$$ millions. Interim results are not annualized. Linked values open their source. $Unavailable means no figure was extracted from the retained sources for that period."), links)
    }
    /** A line the filing does not report directly, such as liabilities computed as assets less equity, says so. */
    def labelled(label: String, item: LineItem, periods: List[Period]): String =
      if (periods.flatMap(record.grid.get(item, _)).exists(_.source.describe.contains(" less "))) s"$label (derived)" else label
    val components = CovenantComponents.build(a)
    val summary = table("FINANCIAL SUMMARY ($ millions)", flows, List(
        "Revenue" -> Revenue, "Income from continuing operations" -> ContinuingIncome,
        "Consolidated net income" -> NetIncomeConsolidated, "Net income attributable to the parent" -> NetIncomeAttributable,
        "Net income available to common" -> CommonIncome, "Operating cash flow" -> OperatingCashFlow,
        "Cash capital expenditures" -> CashCapex))
    val financials = summary.copy(rows = summary.rows ++ components.rows,
      note = Some(summary.note.getOrElse("") + " " + components.note.getOrElse("")),
      links = summary.links ++ components.links.map { case ((row, column), link) => (row + summary.rows.size, column) -> link })
    Map(
      "financials" -> financials,
      "income-statement" -> table("INCOME STATEMENT ($ millions)", flows, List(
        "Revenue" -> Revenue, "Cost of revenue" -> CostOfRevenue, "Depreciation, depletion and amortization" -> Depreciation,
        "Operating income (loss)" -> OperatingIncome, "Interest expense" -> InterestExpense,
        "Pretax income from continuing operations" -> PretaxIncome, "Income tax expense (benefit)" -> IncomeTax,
        "Income from continuing operations" -> ContinuingIncome, "Consolidated net income" -> NetIncomeConsolidated,
        "Net income attributable to the parent" -> NetIncomeAttributable, "Net income available to common" -> CommonIncome)),
      "balance-sheet" -> table("BALANCE SHEET ($ millions)", balances, List(
        "Cash and cash equivalents" -> Cash, "Available-for-sale securities, current" -> AvailableForSaleSecurities,
        "Receivables, net" -> Receivables, "Inventories" -> Inventory, "Total current assets" -> CurrentAssets,
        "Property, plant and equipment, net" -> PropertyPlantEquipment, "Goodwill" -> Goodwill,
        "Other noncurrent assets" -> OtherNoncurrentAssets, "Total assets" -> TotalAssets,
        "Accounts payable" -> AccountsPayable, "Current portion of debt" -> CurrentDebt,
        "Total current liabilities" -> CurrentLiabilities, "Long-term debt, carrying amount" -> LongTermDebtCarrying,
        "Deferred tax liabilities, net" -> DeferredTaxes, "Other noncurrent liabilities" -> OtherNoncurrentLiabilities,
        "Total liabilities" -> TotalLiabilities, "Preferred equity (included in equity)" -> PreferredEquity,
        "Noncontrolling interests (included in equity)" -> NoncontrollingInterest, "Total equity" -> TotalEquity))
    )
  }
}
