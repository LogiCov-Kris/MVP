package logicov.rating

object Anchors {
  private[rating] val FcfToDebt = List[BigDecimal](0.40, 0.30, 0.25, 0.17, 0.10, 0.06, 0.03, 0.0)
  private[rating] val Coverage = List[BigDecimal](20, 14, 8, 6, 4.5, 3.5, 2.5, 1.5, 1.0)
  private[rating] val Liquidity = List[BigDecimal](5, 3, 2, 1.5, 1.2, 1.0, 0.8, 0.5)
  private[rating] val ScaleBillions = List[BigDecimal](30, 15, 5, 1.5, 0.75, 0.4, 0.2, 0.1)
  private[rating] val LargestShare = List[BigDecimal](0.30, 0.40, 0.50, 0.70, 0.80, 0.90, 0.95, 0.99)
  private[rating] val PriceLinkedShare = List[BigDecimal](0.0, 0.02, 0.05, 0.15, 0.49)

  def leverage(netLeverage: BigDecimal, ebitda: BigDecimal, ladder: Ladder): Int =
    if (ebitda <= 0) 10 else atMost(netLeverage, ladder.leverage)

  def fcfToDebt(ratio: BigDecimal): Int = atLeast(ratio, FcfToDebt)

  def coverage(ratio: BigDecimal, ladder: Ladder): Int = atLeast(ratio, Coverage.map(_ * ladder.coverageFactor))

  def liquidity(cover: BigDecimal, netCash: Boolean): Int = atLeast(cover, Liquidity) match {
    case 1 if !netCash => 2
    case score => score
  }

  def scale(ebitdaBillions: BigDecimal): Int = if (ebitdaBillions <= 0) 10 else atLeast(ebitdaBillions, ScaleBillions)

  def diversification(largestShare: BigDecimal, singleCommodity: Boolean): Int = {
    val base = if (largestShare >= 1) 10 else atMost(largestShare, LargestShare)
    if (singleCommodity) math.min(base + 1, 10) else base
  }

  def industry(priceLinkedShare: BigDecimal, severeTrough: Boolean): Int = {
    val base = atMost(priceLinkedShare, PriceLinkedShare)
    if (base == 6 && severeTrough) 7 else base
  }

  private[rating] def atMost(value: BigDecimal, thresholds: List[BigDecimal]): Int = position(thresholds.indexWhere(value <= _), thresholds)

  private[rating] def atLeast(value: BigDecimal, thresholds: List[BigDecimal]): Int = position(thresholds.indexWhere(value >= _), thresholds)

  private def position(index: Int, thresholds: List[BigDecimal]): Int = if (index == -1) thresholds.size + 1 else index + 1
}
