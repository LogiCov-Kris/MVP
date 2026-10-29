package logicov.analysis

import fabric.rw.*
import logicov.grid.Period
import logicov.rating.{Ladder, RatingOverride, Support}
import logicov.source.Sourced

case class CreditProfile(facility: Facility,
                         commitment: Sourced[BigDecimal],
                         availability: Sourced[BigDecimal],
                         covenant: Covenant,
                         ltmEbitda: Option[Sourced[BigDecimal]],
                         annualEbitda: List[AnnualFigure],
                         segments: List[Segment],
                         largestShare: Sourced[BigDecimal],
                         priceLinkedShare: Sourced[BigDecimal],
                         concentrationBasis: String,
                         commodityModifier: Option[String],
                         contractLifeYears: Option[Sourced[BigDecimal]],
                         customerOverTenPercent: Boolean,
                         support: Support,
                         agencyRatings: List[AgencyRating],
                         ratingOverride: Option[RatingOverride],
                         stressScenarios: List[StressScenario],
                         fundedDebt: List[FundedDebt],
                         proForma: Option[ProForma]) derives RW {
  def ratingEbitda(periods: SpreadPeriods): (BigDecimal, Period) = ltmEbitda match {
    case Some(ltm) => ltm.value -> periods.latest
    case None => annual(periods.lastYear) -> Period.yearEnd(periods.lastYear)
  }

  def scaleEbitda(periods: SpreadPeriods): BigDecimal =
    if (periods.fiscalYears.forall(y => annualEbitda.exists(_.year == y))) {
      periods.fiscalYears.map(annual).sum / periods.fiscalYears.size
    } else {
      ratingEbitda(periods)._1
    }

  def ladder: Ladder =
    Ladder.select(1 - priceLinkedShare.value, contractLifeYears.fold(BigDecimal(0))(_.value), customerOverTenPercent)

  def ebitdaFor(year: Int): Option[BigDecimal] = annualEbitda.find(_.year == year).map(_.value)

  private def annual(year: Int): BigDecimal = annualEbitda.find(_.year == year).map(_.value).getOrElse {
    throw new NoSuchElementException(s"No EBITDA for FY$year and no LTM EBITDA")
  }
}
