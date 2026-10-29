package logicov.grid

import logicov.ingest.{ImportIssue, ImportIssueKind}

object GridValidation {
  def check(grid: GoldenGrid): List[ImportIssue] = {
    val duplicates = grid.values.groupBy(v => v.item -> v.period).collect {
      case ((item, period), values) if values.size > 1 => ImportIssue(ImportIssueKind.Duplicate, period.label, s"Duplicate $item")
    }.toList
    val balances = grid.values.map(_.period).filter(_.isInstant).distinct.flatMap { period =>
      for {
        assets <- grid.get(LineItem.TotalAssets, period)
        liabilities <- grid.get(LineItem.TotalLiabilities, period)
        equity <- grid.get(LineItem.TotalEquity, period)
        difference = assets.value - liabilities.value - equity.value
        if difference.abs > BigDecimal("0.001")
      } yield ImportIssue(ImportIssueKind.Reconciliation, period.label,
        s"Assets less liabilities and equity differs by $difference million; review source rounding and extraction")
    }
    duplicates ++ balances
  }
}
