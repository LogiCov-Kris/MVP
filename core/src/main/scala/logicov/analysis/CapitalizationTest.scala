package logicov.analysis

/**
 * A maximum debt-to-capitalization covenant tested on carrying debt and total equity.
 */
case class CapitalizationTest(threshold: BigDecimal, debt: BigDecimal, equity: BigDecimal) {
  def actual(addedDebt: BigDecimal, equityChange: BigDecimal = 0): BigDecimal = {
    require(debt + addedDebt + equity + equityChange > 0, "Capitalization must be positive")
    (debt + addedDebt) / (debt + addedDebt + equity + equityChange)
  }

  def headroom(addedDebt: BigDecimal, equityChange: BigDecimal = 0): BigDecimal =
    threshold - actual(addedDebt, equityChange)

  /** The fall in equity, as a share of equity, that takes the ratio to the threshold at constant debt. */
  def equityDeclineToThreshold: BigDecimal = 1 - debt * (1 - threshold) / threshold / equity
}
