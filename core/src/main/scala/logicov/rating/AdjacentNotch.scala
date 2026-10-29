package logicov.rating

import scala.math.BigDecimal.RoundingMode

/**
 * Why the calculated risk rating is not the adjacent notch (Risk Rating Guidance v2.2, Section 5). Financial ratios are
 * tried before structural ones (scale, concentration, industry), one ratio before two, each moving one or two thresholds,
 * in the order a credit officer reads them. Returns the first path that reaches the notch and its composite.
 */
object AdjacentNotch {
  private val MaxSteps = 2
  private val FinancialAnchors = 4

  def stronger(inputs: RatingInputs): Option[NotchPath] = search(inputs, stronger = true)

  def weaker(inputs: RatingInputs): Option[NotchPath] = search(inputs, stronger = false)

  private def search(inputs: RatingInputs, stronger: Boolean): Option[NotchPath] = {
    val current = RatingEngine.score(inputs)
    val grade = MasterScale.fromComposite(RatingEngine.compositeOf(current))
    MasterScale.grades.lift(MasterScale.notch(grade) + (if (stronger) -1 else 1)).flatMap { target =>
      val threshold = if (stronger) grade.compositeFrom - BigDecimal("0.01") else target.compositeFrom
      def moves(group: List[RatioAnchor]) = group.map(a => (1 to MaxSteps).toList.flatMap(a.step(stronger, _)))
      def singles(group: List[RatioAnchor]) = moves(group).flatten.map(List(_))
      def pairs(group: List[RatioAnchor]) =
        moves(group).combinations(2).toList.flatMap { case List(a, b) => for (x <- a; y <- b) yield List(x, y) }
      val (financial, structural) = anchors(inputs).splitAt(FinancialAnchors)
      def reaching(candidates: List[List[AnchorMove]]) = candidates.view.flatMap { moves =>
        val moved = moves.foldLeft(inputs)((i, m) => m.change(i))
        val scores = RatingEngine.score(moved)
        val composite = RatingEngine.compositeOf(scores)
        val reaches = if (stronger) composite <= threshold else composite >= threshold
        Option.when(reaches)(NotchPath(target, threshold, moves, composite, scores.filterNot(current.contains)))
      }.headOption
      reaching(singles(financial)).orElse(reaching(pairs(financial))).orElse(reaching(singles(structural)))
        .orElse(reaching(pairs(financial ++ structural)))
    }
  }

  private def anchors(i: RatingInputs): List[RatioAnchor] = List(
    RatioAnchor("Net debt / EBITDA", i.netLeverage, i.ladder.leverage, atMost = true, multiple, (r, v) => r.copy(netLeverage = v)),
    RatioAnchor("EBITDA / interest", i.interestCoverage, Anchors.Coverage.map(_ * i.ladder.coverageFactor), atMost = false, multiple,
      (r, v) => r.copy(interestCoverage = v)),
    RatioAnchor("Liquidity cover", i.liquidityCover, Anchors.Liquidity, atMost = false, multiple, (r, v) => r.copy(liquidityCover = v)),
    RatioAnchor("FCF / debt", i.fcfToDebt, Anchors.FcfToDebt, atMost = false, percent, (r, v) => r.copy(fcfToDebt = v)),
    RatioAnchor("Scale EBITDA", i.scaleEbitda / 1000, Anchors.ScaleBillions, atMost = false, v => s"$$${v.bigDecimal.stripTrailingZeros.toPlainString}bn",
      (r, v) => r.copy(scaleEbitda = v * 1000)),
    RatioAnchor(i.concentrationBasis, i.largestShare, Anchors.LargestShare, atMost = true, percent, (r, v) => r.copy(largestShare = v)),
    RatioAnchor("Commodity-price-linked share", i.priceLinkedShare, Anchors.PriceLinkedShare, atMost = true, percent,
      (r, v) => r.copy(priceLinkedShare = v))
  )

  private def multiple(v: BigDecimal): String = s"${v.setScale(2, RoundingMode.HALF_UP)}x"

  private def percent(v: BigDecimal): String = s"${(v * 100).setScale(0, RoundingMode.HALF_UP)}%"
}
