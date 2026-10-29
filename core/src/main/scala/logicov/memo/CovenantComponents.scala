package logicov.memo

import logicov.analysis.{CovenantKind, CreditAnalysis}
import logicov.grid.{LineItem, Period}

/** Period-aligned rows appended directly to the financial summary. */
object CovenantComponents {
  def build(analysis: CreditAnalysis): MemoTable = {
    val record = analysis.record
    val grid = record.grid
    val periods = (record.periods.fiscalYears.map(Period.yearEnd) :+ record.periods.latest).distinct
    val flows = (record.periods.fiscalYears.map(Period.fiscalYear) :+ record.periods.interim).distinct
    val audit = s"${record.ticker.toLowerCase}-audit.html"
    def amount(item: LineItem, period: Period): Option[BigDecimal] = grid.get(item, period).map(_.value)
    def gross(period: Period): Option[BigDecimal] = amount(LineItem.DebtPrincipal, period)
      .map(_ + amount(LineItem.FinanceLeases, period).getOrElse(BigDecimal(0)))
    def carrying(period: Period): Option[BigDecimal] = for {
      current <- amount(LineItem.CurrentDebt, period)
      longTerm <- amount(LineItem.LongTermDebtCarrying, period)
    } yield current + longTerm
    def row(label: String, values: List[Option[BigDecimal]]): List[String] =
      label :: values.map(_.fold("—")(Format.millions))
    val rows = record.profile.covenant.kind match {
      case CovenantKind.MaxNetLeverage =>
        val ebitda = flows.map { period =>
          if (record.periods.fiscalYears.contains(period.end.getYear)) {
            record.profile.ebitdaFor(period.end.getYear).fold("—")(Format.millions)
          } else record.profile.ltmEbitda match {
            case Some(value) => s"${Format.millions(value.value)} (LTM)"
            case None => s"${Format.millions(analysis.ratios.ebitda)} (FY${record.periods.lastYear})"
          }
        }
        List(row("Gross debt (principal plus finance leases)", periods.map(gross)),
          row("Less: cash and cash equivalents", periods.map(amount(LineItem.Cash, _))),
          row("Net debt", periods.map(p => for { debt <- gross(p); cash <- amount(LineItem.Cash, p) } yield debt - cash)),
          "EBITDA" :: ebitda)
      case CovenantKind.MaxDebtToCapitalization =>
        List(row("Debt (carrying amount)", periods.map(carrying)),
          row("Total equity", periods.map(amount(LineItem.TotalEquity, _))),
          row("Total capitalization", periods.map(p => for { debt <- carrying(p); equity <- amount(LineItem.TotalEquity, p) } yield debt + equity)))
    }
    val links = (for {
      (values, r) <- rows.zipWithIndex
      (value, c) <- values.tail.zipWithIndex if value != "—"
    } yield (r, c + 1) -> s"$audit#sources").toMap
    val basis = if (record.profile.covenant.kind == CovenantKind.MaxNetLeverage)
      " EBITDA is full-year in the FY columns; the latest column explicitly identifies the LTM or prior-year EBITDA used in analysis, not interim earnings." else ""
    MemoTable("COVENANT COMPONENT DETAIL ($ millions)", "" :: flows.map(_.label), rows,
      Some("Debt, cash, equity and capitalization are period-end balances." + basis +
        " Agreement definitions and the compliance certificate govern." +
        (if (record.profile.covenant.indicative) " Covenant components are indicative pending agreement verification." else "")), links)
  }
}
