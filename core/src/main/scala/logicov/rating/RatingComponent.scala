package logicov.rating

enum RatingComponent(val label: String, val weight: BigDecimal) {
  case LeverageAndCashFlow extends RatingComponent("Leverage and FCF", 0.20)
  case CoverageAndLiquidity extends RatingComponent("Coverage and liquidity", 0.20)
  case Scale extends RatingComponent("Scale and asset quality", 0.18)
  case Diversification extends RatingComponent("Diversification", 0.12)
  case Industry extends RatingComponent("Industry and macro", 0.15)
  case Support extends RatingComponent("Support / parent", 0.15)
}
