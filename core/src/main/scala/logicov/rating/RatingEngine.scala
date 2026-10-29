package logicov.rating

import scala.math.BigDecimal.RoundingMode

object RatingEngine {
  val SeniorUnsecuredLgd: BigDecimal = 0.40

  def rate(inputs: RatingInputs,
           exposure: BigDecimal,
           ratingOverride: Option[RatingOverride] = None,
           lgd: BigDecimal = SeniorUnsecuredLgd): RatingResult = {
    val components = score(inputs)
    val composite = compositeOf(components)
    RatingResult(components, composite, MasterScale.fromComposite(composite), ratingOverride, exposure, lgd, inputs)
  }

  def score(inputs: RatingInputs): List[ComponentScore] =
    List(leverage(inputs), coverage(inputs), scale(inputs), diversification(inputs), industry(inputs), support(inputs))

  def compositeOf(components: List[ComponentScore]): BigDecimal = components.map(_.weighted).sum.setScale(2, RoundingMode.HALF_UP)

  private def leverage(i: RatingInputs): ComponentScore = {
    val primary = Anchors.leverage(i.netLeverage, i.ebitda, i.ladder)
    val secondary = Anchors.fcfToDebt(i.fcfToDebt)
    val score = if (secondary - primary >= 2) primary + 1 else if (primary - secondary >= 2) primary - 1 else primary
    ComponentScore(RatingComponent.LeverageAndCashFlow, score,
      s"Net debt / EBITDA ${fmt(i.netLeverage)}x (Ladder ${i.ladder}) = $primary; FCF / debt ${pct(i.fcfToDebt)} = $secondary")
  }

  private def coverage(i: RatingInputs): ComponentScore = {
    val interest = Anchors.coverage(i.interestCoverage, i.ladder)
    val liquidity = Anchors.liquidity(i.liquidityCover, i.netCash)
    ComponentScore(RatingComponent.CoverageAndLiquidity, (interest + liquidity + 1) / 2,
      s"EBITDA / interest ${fmt(i.interestCoverage)}x = $interest; liquidity cover ${liquidityText(i.liquidityCover)} = $liquidity")
  }

  private def scale(i: RatingInputs): ComponentScore = {
    val billions = i.scaleEbitda / 1000
    val basis = if (i.scaleEbitda == i.ebitda) "EBITDA" else "3-year average EBITDA"
    ComponentScore(RatingComponent.Scale, Anchors.scale(billions), s"$basis $$${fmt(billions, 1)}bn")
  }

  private def diversification(i: RatingInputs): ComponentScore = ComponentScore(RatingComponent.Diversification,
    Anchors.diversification(i.largestShare, i.commodityModifier.isDefined),
    s"${i.concentrationBasis} ${pct(i.largestShare)}${i.commodityModifier.fold("")(c => s"; +1, over 85% of revenue priced off $c")}")

  private def industry(i: RatingInputs): ComponentScore = ComponentScore(RatingComponent.Industry,
    Anchors.industry(i.priceLinkedShare, i.severeTrough), s"Commodity-price-linked share ${pct(i.priceLinkedShare)}")

  private def support(i: RatingInputs): ComponentScore =
    ComponentScore(RatingComponent.Support, i.support.score, s"Support: ${i.support.label}")

  /** Five times is the cover recorded when nothing matures within 12 months. */
  private def liquidityText(cover: BigDecimal): String = if (cover == 5) "≥5x (no maturities within 12 months)" else s"${fmt(cover)}x"

  private def fmt(value: BigDecimal, scale: Int = 2): String = value.setScale(scale, RoundingMode.HALF_UP).toString

  private def pct(value: BigDecimal): String = s"${fmt(value * 100, 1)}%"
}
