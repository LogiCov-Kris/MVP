package logicov.grid

import logicov.edgar.{CompanyFacts, XbrlFact, XbrlInstance}
import logicov.source.Source

object GridBuilder {
  private val Million = BigDecimal(1000000)

  def fromXbrl(facts: CompanyFacts, periods: List[Period], instances: List[XbrlInstance] = Nil): GoldenGrid = {
    val values = for {
      period <- periods
      item <- LineItem.values.toList if item.flow != period.isInstant
      value <- (if (item == LineItem.CommonIncome) commonIncome(instances, period).orElse(lookup(facts, item, period))
        else lookup(facts, item, period))
    } yield value
    GoldenGrid(facts.entityName, values)
  }

  /** Companyfacts omits stock-class facts; accept a single disclosed common-stock class without summing classes. */
  private def commonIncome(instances: List[XbrlInstance], period: Period): Option[GridValue] =
    instances.view.flatMap { instance =>
      val found = instance.facts.filter(f => f.concept == "NetIncomeLossAvailableToCommonStockholdersBasic" &&
        f.start == period.start && f.end == period.end && f.isUsd &&
        f.dimensions.keySet == Set("StatementClassOfStockAxis"))
      Option.when(found.size == 1)(found.head).map { f =>
        GridValue(LineItem.CommonIncome, period, f.amount / Million,
          Source.Xbrl(s"${f.concept} (${f.dimensions("StatementClassOfStockAxis")})", instance.form, instance.accession))
      }
    }.headOption

  private def lookup(facts: CompanyFacts, item: LineItem, period: Period): Option[GridValue] =
    item.tags.view.flatMap(tag => value(facts, tag, period).map(v => GridValue(item, period, v._1, v._2))).headOption

  private def value(facts: CompanyFacts, tag: XbrlTag, period: Period): Option[(BigDecimal, Source)] = for {
    primary <- fact(facts, tag.name, period)
    subtracted <- all(facts, tag.less, period)
    added <- all(facts, tag.plus, period)
  } yield {
    val amount = (primary.value - subtracted.map(_.value).sum + added.map(_.value).sum) / Million
    val name = (tag.name :: tag.less.map(n => s"less $n") ++ tag.plus.map(n => s"plus $n")).mkString(" ")
    (if (tag.negate) -amount else amount) -> Source.Xbrl(name, primary.form, primary.accession)
  }

  /** Every named concept, or None when any is missing for the period. */
  private def all(facts: CompanyFacts, names: List[String], period: Period): Option[List[XbrlFact]] = {
    val found = names.flatMap(fact(facts, _, period))
    if (found.size == names.size) Some(found) else None
  }

  private def fact(facts: CompanyFacts, tag: String, period: Period): Option[XbrlFact] = period.start match {
    case Some(start) => facts.duration(tag, start, period.end)
    case None => facts.instant(tag, period.end)
  }
}
