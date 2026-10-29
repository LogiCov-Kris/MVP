package logicov.deal

import logicov.analysis.*
import logicov.edgar.*
import logicov.facts.{AgencyRatingsText, FacilityTerms}
import logicov.grid.{GridBuilder, GridValue, LineItem, Period}
import logicov.memo.Format
import logicov.source.{Source, Sourced}

import java.time.LocalDate

/**
 * Builds the deal record from a source pack. Every value is extracted from the filings or the discovery call,
 * except the judgments in the pack's analyst inputs.
 */
object DealBuilder {
  private val Million = BigDecimal(1000000)
  private val CompanyCapex = """.*CapitalExpenditures.*""".r
  private val CreditFacilityAxis = "CreditFacilityAxis"
  private val InterimLabels = Map(3 -> "Q1", 6 -> "H1", 9 -> "9M")

  def build(pack: SourcePack): DealRecord = {
    val inputs = pack.inputs
    val call = pack.call
    val periods = spreadPeriods(pack.annual.periodEnd, pack.interim.periodEnd)
    val years = periods.fiscalYears
    val segments = SegmentExtractor.extract(pack.annual, years, inputs.priceLinkedSegments)
    val commitment = call.money("Amount")
    val profile = CreditProfile(
      facility = FacilityTerms.parse(call),
      commitment = commitment,
      availability = availability(pack, commitment),
      covenant = covenant(pack),
      ltmEbitda = inputs.ebitda.ltmField.map(call.money(_)),
      annualEbitda = annualEbitda(pack, years),
      segments = segments,
      largestShare = inputs.concentration.map(c => call.percent(c.field, c.marker)).getOrElse(SegmentShares.largest(segments, years)),
      priceLinkedShare = SegmentShares.priceLinked(segments, periods.lastYear),
      concentrationBasis = inputs.concentration.map(_.label)
        .getOrElse(s"Largest segment share of ${years.size}-year ${SegmentShares.measure(segments, years)}"),
      commodityModifier = inputs.commodityModifier,
      contractLifeYears = ContractLife.find(pack.text(pack.manifest.one(FilingRole.AnnualReport)), pack.annual.form, pack.annual.accession),
      customerOverTenPercent = inputs.customerOverTenPercent,
      support = inputs.support,
      agencyRatings = AgencyRatingsText.parse(call("Agency ratings"), Source.CallScript("Agency ratings")).map { rating =>
        inputs.agencyUpdates.find(_.house == rating.house).fold(rating)(u =>
          rating.copy(outlook = u.outlook.getOrElse(rating.outlook), asOf = u.asOf.getOrElse(rating.asOf)))
      },
      ratingOverride = inputs.ratingOverride,
      stressScenarios = inputs.stressScenarios,
      fundedDebt = DebtSchedule.rows(pack.interim, pack.annual, call),
      proForma = inputs.proForma
    )
    val facts = pack.companyFacts
    DealRecord(call("Borrower"), inputs.ticker, facts.cik, periods, GridBuilder.fromXbrl(facts, periods.all, List(pack.interim, pack.annual)), profile, call.fields,
      inputs.peers.map(peer(pack, _, periods.lastYear)), inputs, pack.contextDocuments,
      SegmentExtractor.extract(pack.interim, List(periods.interim.end.getYear), inputs.priceLinkedSegments, Some(periods.interim.end)),
      pack.officers.map { officer =>
        if (officer.background.nonEmpty) officer
        else pack.manifest.all(FilingRole.Proxy).view.flatMap { filing =>
          OfficerSection.biography(pack.text(filing), officer.name).map { biography =>
            officer.copy(background = biography, source = Source.Exhibit(filing.form, filing.accession,
              "director and executive officer biographies", "names, titles, ages and backgrounds as written"))
          }
        }.headOption.getOrElse(officer)
      })
  }

  def spreadPeriods(annualEnd: LocalDate, interimEnd: LocalDate): SpreadPeriods = {
    val year = annualEnd.getYear
    val label = InterimLabels.getOrElse(interimEnd.getMonthValue, s"${interimEnd.getMonthValue}M")
    SpreadPeriods(
      fiscalYears = List(year - 2, year - 1, year),
      interim = Period.interim(s"$label ${interimEnd.getYear}", LocalDate.of(year + 1, 1, 1), interimEnd),
      latest = Period.balance(Format.date(interimEnd), interimEnd)
    )
  }

  private def availability(pack: SourcePack, commitment: Sourced[BigDecimal]): Sourced[BigDecimal] = {
    val interim = pack.interim
    interim.facts.find(f => f.concept == "LineOfCreditFacilityRemainingBorrowingCapacity" && f.start.isEmpty &&
      f.end == interim.periodEnd && f.isUsd && f.dimensions.keySet == Set(CreditFacilityAxis)) match {
      case Some(fact) => Sourced(fact.amount / Million, Source.Xbrl("LineOfCreditFacilityRemainingBorrowingCapacity", interim.form, interim.accession))
      case None if pack.call("Drawn status").toLowerCase.startsWith("undrawn") => Sourced(commitment.value, Source.CallScript("Drawn status"))
      case None => throw new IllegalStateException("Availability is neither tagged in the 10-Q nor undrawn per the call")
    }
  }

  private def covenant(pack: SourcePack): Covenant = {
    val text = pack.call("Financial covenant")
    val kind = if (text.toLowerCase.contains("capitalization")) CovenantKind.MaxDebtToCapitalization else CovenantKind.MaxNetLeverage
    Covenant(kind, pack.call.multiple("Financial covenant"), pack.inputs.covenantIndicative)
  }

  private def annualEbitda(pack: SourcePack, years: List[Int]): List[AnnualFigure] = {
    val basis = pack.inputs.ebitda
    val fromReleases = basis.releaseLabel.toList.flatMap { label =>
      val byYear = pack.manifest.all(FilingRole.EarningsRelease).sortBy(_.periodEnd).flatMap { release =>
        EarningsRelease.annual(pack.text(release), label, release.periodEnd.getYear).map { (year, value) =>
          year -> AnnualFigure(year, value, Source.Exhibit(release.form, release.accession, "Exhibit 99.1 earnings release", label))
        }
      }.toMap
      years.flatMap(byYear.get)
    }
    fromReleases ++ basis.spread.toList.flatMap(spread => years.map(spreadYear(pack.annual, spread, _)))
  }

  private def spreadYear(annual: XbrlInstance, spread: EbitdaSpread, year: Int): AnnualFigure = {
    val concepts = spread.base :: spread.addBacks
    val amounts = concepts.map { concept =>
      annual.amount(concept, Some(LocalDate.of(year, 1, 1)), LocalDate.of(year, 12, 31)).map(_ / Million)
        .getOrElse(throw new NoSuchElementException(s"${annual.form} has no FY$year $concept for the EBITDA spread"))
    }
    val arithmetic = s"${amounts.map(Format.millions).mkString(" + ")} = ${Format.millions(amounts.sum)}"
    val formula = s"FY$year EBITDA spread $arithmetic (${concepts.map(c => annual.label(c).toLowerCase).mkString(" + ")}; ${annual.form} XBRL)"
    AnnualFigure(year, amounts.sum, Source.Derived(formula))
  }

  private def peer(pack: SourcePack, input: PeerInput, year: Int): Peer = {
    val facts = pack.peerFacts(input.ticker)
    val grid = GridBuilder.fromXbrl(facts, List(Period.fiscalYear(year), Period.yearEnd(year)))
    val capex = Option.when(grid.get(LineItem.CashCapex, Period.fiscalYear(year)).isEmpty)(())
      .flatMap(_ => pack.peerAnnual(input.ticker)).flatMap(companyCapex(_, year))
    Peer(input.ticker, facts.cik, capex.fold(grid)(v => grid.copy(values = grid.values :+ v)), input.ratings, input.ratingsSource,
      pack.peerRatings(input.ticker))
  }

  /** Capital expenditure a peer reports only under its own concept, such as "CapitalExpendituresAndInvestments". */
  private def companyCapex(annual: XbrlInstance, year: Int): Option[GridValue] = {
    val fy = Period.fiscalYear(year)
    annual.facts.find(f => f.isUsd && f.dimensions.isEmpty && f.start == fy.start && f.end == fy.end && CompanyCapex.matches(f.concept))
      .map(f => GridValue(LineItem.CashCapex, fy, f.amount / Million, Source.Xbrl(f.concept, annual.form, annual.accession)))
  }
}
