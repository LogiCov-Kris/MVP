package logicov.analysis

import logicov.source.{Source, Sourced}

import scala.math.BigDecimal.RoundingMode

object SegmentShares {
  def largest(segments: List[Segment], years: List[Int]): Sourced[BigDecimal] = {
    val (measure, values) = basis(segments, years)
    val totals = values.map(byYear => years.map(byYear).sum)
    Sourced(share(totals.max, totals.sum), Source.Derived(s"Largest segment share of $measure, ${years.size}-year total"))
  }

  def priceLinked(segments: List[Segment], year: Int): Sourced[BigDecimal] = {
    val (measure, values) = basis(segments, List(year))
    val linked = segments.zip(values).filter(_._1.priceLinked).map(_._2(year)).sum
    Sourced(share(linked, values.map(_(year)).sum), Source.Derived(s"Commodity-price-linked share of $year $measure"))
  }

  private def share(part: BigDecimal, whole: BigDecimal): BigDecimal = (part / whole).setScale(6, RoundingMode.HALF_UP)

  def measure(segments: List[Segment], years: List[Int]): String = basis(segments, years)._1

  private def basis(segments: List[Segment], years: List[Int]): (String, List[Map[Int, BigDecimal]]) =
    if (segments.exists(s => years.exists(y => s.earnings.get(y).exists(_ < 0)))) {
      "sales" -> segments.map(_.sales)
    } else {
      "segment earnings" -> segments.map(_.earnings)
    }
}
