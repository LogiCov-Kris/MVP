package logicov.analysis

case class CovenantHeadroom(covenant: Covenant,
                            actual: BigDecimal,
                            debtCapacity: BigDecimal,
                            ebitdaAtThreshold: Option[BigDecimal]) {
  def threshold: BigDecimal = covenant.threshold.value

  def headroom: BigDecimal = threshold - actual
}

object CovenantHeadroom {
  def compute(covenant: Covenant, ratios: KeyRatios, carryingDebt: BigDecimal, equity: BigDecimal): CovenantHeadroom = {
    val t = covenant.threshold.value
    covenant.kind match {
      case CovenantKind.MaxNetLeverage =>
        CovenantHeadroom(covenant, ratios.netLeverage, t * ratios.ebitda - ratios.netDebt, Some(ratios.netDebt / t))
      case CovenantKind.MaxDebtToCapitalization =>
        val capitalization = carryingDebt + equity
        CovenantHeadroom(covenant, carryingDebt / capitalization, (t * capitalization - carryingDebt) / (1 - t), None)
    }
  }
}
