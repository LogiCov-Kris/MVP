package logicov.memo

import logicov.analysis.{CovenantKind, CreditAnalysis, KeyRatios, SegmentShares}
import logicov.deal.{DealRecord, Peer}
import logicov.facts.FacilityTerms
import logicov.grid.{GoldenGrid, LineItem, Period}
import logicov.rating.{MasterScale, RatingComponent}
import logicov.source.Source

object MemoTables {
  private val LeadershipRole = """(?i).*\b(CEO|CFO|Chairman|President|Officer)\b.*""".r
  private val Unavailable = "—"
  private val CallFacility = List("Borrower", "Request", "Amount", "Tenor", "Existing facility", "Administrative agent", "Purpose",
    "Ranking / security", "Drawn status", "Financial covenant", "Swingline", "LC sublimit", "Accordion", "Drawn margin",
    "Commitment fee", "Existing pricing grid", "Expected pricing (per management)", "Requested tenor", "Current maturity",
    "Prior maturity", "Sustainability KPI")
  private val NotPulled = "n.p."
  private val NotApplicable = "n.a."
  private val NotMeaningful = "n.m."
  private val TopOfficers = 6
  private val Departed = 7
  private val BriefWords = 30
  private val OutsideParentheses = """;(?![^()]*\))"""

  def build(analysis: CreditAnalysis): Map[String, MemoTable] = {
    val record = analysis.record
    val grid = record.grid
    val periods = record.periods
    val years = periods.fiscalYears.map(Period.fiscalYear)
    val flows = (years :+ periods.interim).distinct
    val balances = (periods.fiscalYears.map(Period.yearEnd) :+ periods.latest).distinct
    Map(
      "financial-covenant-components" -> CovenantComponents.build(analysis),
      "segments" -> segments(analysis),
      "interim-segments" -> MemoTable(s"SEGMENTS: ${periods.interim.label} ($$ millions)",
        List("Segment", "Revenue", "Segment earnings"), record.interimSegments.map(s =>
          List(s.name, cell(s.sales.get(periods.interim.end.getYear)), cell(s.earnings.get(periods.interim.end.getYear)))),
        Some(if (record.interimSegments.isEmpty) "Interim segment detail was not extracted; consult the retained interim filing."
          else "Interim segment results from the retained 10-Q. These are not full-year results.")),
      "funded-debt" -> MemoTable("FUNDED DEBT BY SOURCE", List("Instrument", "Terms", "Amount or status"),
        record.profile.fundedDebt.map(d => List(d.instrument, d.terms, d.status)),
        None),
      "debt" -> MemoTable("DEBT RECONCILIATION ($ millions)", "" :: balances.map(_.label), List(
        row(grid, "Current portion of debt", LineItem.CurrentDebt, balances),
        row(grid, "Long-term debt (carrying)", LineItem.LongTermDebtCarrying, balances),
        "Total carrying debt (balance sheet)" :: balances.map(p => cell(carrying(grid, p))),
        row(grid, "Debt principal (face)", LineItem.DebtPrincipal, balances),
        row(grid, "Finance leases", LineItem.FinanceLeases, balances),
        "Gross debt (Grid: principal plus finance leases)" :: balances.map(p => cell(gross(grid, p))),
        row(grid, "Less cash and cash equivalents", LineItem.Cash, balances),
        "Net debt (Grid)" :: balances.map(p => cell(gross(grid, p).flatMap(g => grid.get(LineItem.Cash, p).map(c => g - c.value))))
      ), Some("Carrying debt includes fair-value adjustments and unamortized discounts; the Grid measures leverage on principal plus finance leases.")),
      "cash-flow" -> MemoTable("CASH FLOW ($ millions)", "" :: flows.map(_.label), withValues(List(
        row(grid, "GAAP operating cash flow (total)", LineItem.OperatingCashFlow, flows),
        row(grid, "Of which: continuing operations", LineItem.ContinuingOperatingCashFlow, flows),
        row(grid, "Of which: discontinued operations", LineItem.DiscontinuedOperatingCashFlow, flows),
        row(grid, "Cash capital expenditures", LineItem.CashCapex, flows),
        "OCF less cash capex (Grid FCF)" :: flows.map(y => cell(fcf(grid, y))),
        row(grid, "Dividends paid (as reported, all classes)", LineItem.DividendsPaid, flows),
        "Grid FCF after dividends" :: flows.map(y => cell(fcf(grid, y).flatMap(f => grid.get(LineItem.DividendsPaid, y).map(d => f - d.value))))
      ))),
      "cash-movements" -> MemoTable("CASH MOVEMENTS ($ millions)", "" :: flows.map(_.label), List(
        row(grid, "Net investing cash flow", LineItem.InvestingCashFlow, flows),
        row(grid, "Net financing cash flow", LineItem.FinancingCashFlow, flows)
      )),
      "key-ratios" -> keyRatios(analysis),
      "pro-forma" -> proForma(analysis),
      "outlook" -> outlook(analysis),
      "leadership" -> leadership(record),
      "peers" -> peers(analysis),
      "facility" -> MemoTable("TERM DETAIL", List("Term", "Detail"),
        CallFacility.flatMap(field => record.callFacts.get(field).map(value => List(field, value))),
        Some("Terms from the discovery call are per management until tied to the credit agreement.")),
      "covenant" -> covenant(analysis),
      "scorecard" -> MemoTable("RISK RATING SCORECARD (Guidance v2.2)", List("Component", "Ratio and anchor result", "Score", "Weight", "Weighted"),
        analysis.rating.components.map(c => List(c.component.label, c.basis, c.score.toString,
          Format.percent(c.component.weight, 0), Format.decimal(c.weighted))) :+
          List("Composite", s"Calculated risk rating ${analysis.rating.machineGrade.mapping}", "", "100%", Format.decimal(analysis.rating.composite)),
        Some(s"PD ${Format.percent(analysis.rating.grade.pd, 2)}, LGD ${Format.percent(analysis.rating.lgd, 0)}, EAD at full draw ${Format.billions(analysis.rating.exposure)}, expected loss $$${Format.decimal(analysis.rating.expectedLoss)} million. Score 1 is strongest.")),
      "scorecard-sources" -> MemoTable("SCORECARD SOURCES", List("Component", "Source"),
        analysis.rating.components.map(c => List(c.component.label, componentSource(analysis, c.component)))),
      "agency-map" -> MemoTable("MAPPING TO AGENCY RATINGS", List("Source", "Rating", "Outlook", "As of", "Notches: internal vs house"),
        List("Internal risk rating (final)", analysis.rating.grade.mapping,
          MemoContext.internalOutlook(record.profile.agencyRatings.map(_.outlook)), "this memo", "—") :: record.profile.agencyRatings.map { a =>
          List(a.house, a.rating, a.outlook, a.asOf, notches(analysis.rating.notchesVersus(a.rating)))
        }, Some(("Discovery call" :: record.inputs.agencyUpdates.map(u => s"${u.house}: ${u.source}")).mkString("Sources: ", "; ", "."))),
      "stress" -> stress(analysis),
      "analyst-inputs" -> analystInputs(analysis),
      "rating-map" -> ratingMap(analysis),
      "sources" -> sources(analysis),
      "filings" -> filings(analysis)
    ) ++ FinancialTables.build(analysis)
  }

  private def keyRatios(analysis: CreditAnalysis): MemoTable = {
    val record = analysis.record
    val grid = record.grid
    val profile = record.profile
    val periods = record.periods
    val years = periods.fiscalYears
    val (ebitda, asOf) = profile.ratingEbitda(periods)
    def yearly(f: Int => Option[String]): List[String] = years.map(y => f(y).getOrElse(NotApplicable))
    def leverage(y: Int) = for {
      e <- profile.ebitdaFor(y)
      g <- gross(grid, Period.yearEnd(y))
      c <- grid.get(LineItem.Cash, Period.yearEnd(y))
    } yield Format.multiple((g - c.value) / e)
    def coverage(y: Int) = for {
      e <- profile.ebitdaFor(y)
      i <- grid.get(LineItem.InterestExpense, Period.fiscalYear(y))
    } yield Format.multiple(e / i.value)
    def fcfToDebt(y: Int) = for {
      f <- fcf(grid, Period.fiscalYear(y))
      g <- gross(grid, Period.yearEnd(y))
    } yield Format.percent(f / g)
    def dividendCover(y: Int) = for {
      o <- grid.get(LineItem.OperatingCashFlow, Period.fiscalYear(y))
      d <- grid.get(LineItem.DividendsPaid, Period.fiscalYear(y))
    } yield Format.multiple(o.value / d.value)
    def capitalization(p: Period) = for {
      g <- gross(grid, p)
      e <- grid.get(LineItem.TotalEquity, p)
    } yield Format.percent(g / (g + e.value))
    val ratios = analysis.ratios
    MemoTable("KEY RATIOS (Financial Analysis Grid)", ("" :: years.map(y => s"FY$y")) :+ s"Rating basis (${asOf.label})", List(
      ("Gross debt ($ millions)" :: years.map(y => cell(gross(grid, Period.yearEnd(y))))) :+ Format.millions(ratios.grossDebt),
      ("EBITDA used ($ millions)" :: yearly(y => profile.ebitdaFor(y).map(Format.millions))) :+ Format.millions(ebitda),
      ("Net debt / EBITDA" :: yearly(leverage)) :+ Format.multiple(ratios.netLeverage),
      ("EBITDA / interest expense" :: yearly(coverage)) :+ Format.multiple(ratios.interestCoverage),
      ("Grid FCF / gross debt" :: yearly(fcfToDebt)) :+ s"${Format.percent(ratios.fcfToDebt)} (3-year average FCF / year-end debt)",
      ("OCF / dividends paid" :: yearly(dividendCover)) :+ NotApplicable,
      ("Gross debt / (gross debt + total equity)" :: years.map(y => capitalization(Period.yearEnd(y)).getOrElse(NotApplicable))) :+
        capitalization(asOf).getOrElse(NotApplicable),
      ("(Cash + availability) / debt due within 12 months" :: years.map(_ => NotApplicable)) :+
        s"${liquidity(ratios.liquidityCover)}${if (asOf == periods.latest) "" else s" at ${periods.latest.label}"}"
    ), Some("Annual ratios use period-end balances and annual EBITDA. The rating basis is shown separately; EBITDA calculations and sources are in the audit companion."))
  }

  private def proForma(analysis: CreditAnalysis): MemoTable = {
    val record = analysis.record
    val profile = record.profile
    val ratios = analysis.ratios
    val latest = record.periods.latest
    val grid = record.grid
    profile.proForma match {
      case Some(pf) =>
        val cash = grid(LineItem.Cash, latest)
        val debt = KeyRatios.grossDebt(grid, latest)
        val ebitda = pf.ebitda.getOrElse(ratios.ebitda)
        val maturities = grid(LineItem.CurrentDebt, latest)
        def cover(c: BigDecimal) = liquidity(KeyRatios.liquidityCover(c, profile.availability.value, maturities))
        MemoTable(s"PRO FORMA AT ${latest.label.toUpperCase} ($$ millions): ${pf.event}", List("", s"Reported ${latest.label}", "Adjustment", "Pro forma"), List(
          List("Cash and cash equivalents", Format.millions(cash), Format.millions(pf.cashChange), Format.millions(cash + pf.cashChange)),
          List("Gross debt (Grid)", Format.millions(debt), Format.millions(pf.debtChange), Format.millions(debt + pf.debtChange)),
          List("Net debt", Format.millions(debt - cash), Format.millions(pf.debtChange - pf.cashChange),
            Format.millions(debt + pf.debtChange - cash - pf.cashChange)),
          List("EBITDA used", s"${Format.millions(ratios.ebitda)} (${ebitdaLabel(analysis)})", "",
            pf.ebitda.fold(s"${Format.millions(ratios.ebitda)} (unchanged)")(e => s"${Format.millions(e)} (per the named event)")),
          List("Net debt / EBITDA", Format.multiple((debt - cash) / ratios.ebitda), "",
            Format.multiple((debt + pf.debtChange - cash - pf.cashChange) / ebitda)),
          List("(Cash + availability) / debt due within 12 months", cover(cash), "", cover(cash + pf.cashChange))
        ), Some(s"Illustration of the named event, applied to the reported balance sheet; not a Company forecast and not scored. Source: ${pf.source.describe}."))
      case None => MemoTable("PRO FORMA", Nil, Nil, Some("No pro forma event in the file."))
    }
  }

  private def peers(analysis: CreditAnalysis): MemoTable = {
    val record = analysis.record
    val fy = Period.fiscalYear(record.periods.lastYear)
    val ye = Period.yearEnd(record.periods.lastYear)
    val columns = (record.ticker, record.grid) :: record.peers.map(p => (p.ticker, p.grid))
    // The analyst's sourced entry wins; otherwise retained company disclosures, with offering ratings identified separately.
    def ratings(peer: Peer) =
      if (peer.ratingsSource.isDefined || peer.disclosedRatings.isEmpty) peer.ratings.replace("n.p.", Unavailable)
      else List("S&P", "Moody's", "Fitch").map(h => peer.disclosedRatings.find(_.house == h)
        .fold(Unavailable)(r => if (r.outlook == "not stated") r.rating else s"${r.rating} ${r.outlook}")).mkString(" / ")
    def line(label: String, item: LineItem, period: Period) = label :: columns.map((_, grid) => available(grid.get(item, period).map(_.value)))
    MemoTable(s"FY${record.periods.lastYear} REPORTED ($$ millions)", "" :: columns.map(_._1), List(
      line("Revenue", LineItem.Revenue, fy),
      line("Net income attributable", LineItem.NetIncomeAttributable, fy),
      line("Operating cash flow", LineItem.OperatingCashFlow, fy),
      line("Cash capital expenditures", LineItem.CashCapex, fy),
      line("Cash and cash equivalents, YE", LineItem.Cash, ye),
      "Total debt, YE (principal plus leases; carrying where principal is not reported)" ::
        columns.map((_, grid) => available(gross(grid, ye).orElse(carrying(grid, ye)).orElse(grid.get(LineItem.ReportedTotalDebt, ye).map(_.value)))),
      line("Total assets, YE", LineItem.TotalAssets, ye),
      line("Total equity, YE", LineItem.TotalEquity, ye),
      "Agency ratings" :: (record.profile.agencyRatings.map(_.rating).mkString(" / ") :: record.peers.map(ratings))
    ), Some {
      val rated = record.peers.flatMap(p => p.ratingsSource.map(s => s"${p.ticker}: $s").orElse(
        p.disclosedRatings.headOption.map(r => s"${p.ticker}: ${r.asOf}")))
      "Figures are from each company's annual report for the same fiscal year; debt bases can differ between companies. " +
        s"$Unavailable means no value was extracted from the retained sources. Ratings are shown in S&P / Moody's / Fitch order. " +
        (if (rated.isEmpty) "No sourced peer ratings are available in the retained pack."
         else rated.mkString("Peer ratings: ", "; ", ". Expected notes ratings describe the cited offering, not a current issuer rating. A dash means no rating was extracted from retained sources."))
    })
  }

  private def covenant(analysis: CreditAnalysis): MemoTable = {
    val c = analysis.covenant
    val kind = c.covenant.kind
    MemoTable("COVENANT AND HEADROOM", List("Measure", "Value"), List(
      List("Covenant", MemoContext.covenantDescription(kind, c.threshold)),
      List("Measured at", measuredAt(analysis)),
      List("Actual", MemoContext.covenantValue(kind, c.actual)),
      List("Headroom", MemoContext.covenantValue(kind, c.headroom)),
      List("Additional debt capacity at the threshold ($ millions)", Format.millions(c.debtCapacity))
    ) ++ c.ebitdaAtThreshold.toList.flatMap(e => List(
      List("EBITDA at the threshold, constant debt ($ millions)", Format.millions(e)),
      List("EBITDA decline to reach the threshold", Format.percent(1 - e / analysis.ratios.ebitda, 0))
    )) ++ analysis.stress.flatMap(_.capitalizationCovenant).toList.map { test =>
      List("Equity decline to reach the threshold, constant debt", Format.percent(test.equityDeclineToThreshold))
    }, Some(if (c.covenant.indicative) "Indicative threshold: the agreement level was not in the file."
      else "Calculated from reported figures; the compliance-certificate figure governs."))
  }

  private def stress(analysis: CreditAnalysis): MemoTable = {
    val kind = analysis.record.profile.covenant.kind
    val rows = analysis.stress.toList.flatMap { grid =>
      analysis.record.profile.stressScenarios.map { scenario =>
        val r = grid.run(scenario)
        val actual = kind match {
          case CovenantKind.MaxNetLeverage => r.leverage
          case CovenantKind.MaxDebtToCapitalization => grid.capitalizationCovenant.map(_.actual(scenario.addedDebt, scenario.equityChange))
        }
        val result = r.covenantHeadroom.fold(NotMeaningful)(h => if (h < 0) "Breach" else "Within limit") +
          (if (analysis.covenant.covenant.indicative) " (indicative)" else "")
        List(scenario.name, Format.millions(r.ebitda), actual.fold(NotMeaningful)(MemoContext.covenantValue(kind, _)),
          MemoContext.covenantValue(kind, analysis.covenant.threshold), r.covenantHeadroom.fold(NotMeaningful)(MemoContext.covenantValue(kind, _)),
          result, Format.millions(r.cashAfterCapexAndDividends))
      }
    }
    val reverse = analysis.stress.flatMap(_.capitalizationCovenant).toList.map { c =>
      List(s"Reverse: equity falls ${Format.percent(c.equityDeclineToThreshold)} at constant debt", "Not applicable",
        Format.percent(c.threshold), Format.percent(c.threshold), Format.percent(0), "At limit", "Not modeled")
    }
    val assumptions = if (kind == CovenantKind.MaxDebtToCapitalization)
      "The covenant is debt / capitalization, not EBITDA leverage. Debt and equity are held constant in EBITDA-only scenarios, so covenant headroom does not change. Losses, retained earnings, cash-funded debt repayments and new borrowing are not automatically modeled. "
    else "The covenant proxy is net debt / EBITDA, with debt held constant except where the scenario explicitly adds debt. "
    MemoTable("COVENANT SENSITIVITY", List("Scenario", "EBITDA ($ mm)", "Covenant ratio", "Limit", "Headroom", "Result", "Cash after capex and dividends ($ mm)"), rows ++ reverse,
      Some(assumptions + "EBITDA reductions flow one-for-one to operating cash flow; interest, capex and dividends remain at the historical base. 20%, 30% and 40% reductions are sensitivities, not forecasts. Agreement definitions and the compliance certificate govern."))
  }

  private def outlook(analysis: CreditAnalysis): MemoTable = {
    val call = analysis.record.callFacts
    val fields = call.toList.filter((key, _) => key.toLowerCase.matches(".*(guid|target|budget|incremental fcf|cash hierarchy|interest run-rate|post-q|post.quarter|outlook).*"))
      .sortBy(_._1)
    MemoTable("FORWARD OUTLOOK: MANAGEMENT GUIDANCE", List("Horizon / subject", "Published guidance or management statement", "Attribution"),
      fields.map((key, value) => List(key, value, call.get("Call date").fold("Management, discovery call")(date => s"Management, discovery call $date"))),
      Some("Guidance is not a historical result or an independent forecast. Agency projections mentioned by management remain attributed to the call until the agency research is obtained."))
  }

  private def segments(analysis: CreditAnalysis): MemoTable = {
    val profile = analysis.record.profile
    val years = analysis.record.periods.fiscalYears
    MemoTable("SEGMENTS ($ millions)", "Segment / measure" :: years.map(y => s"FY$y"),
      profile.segments.flatMap { s =>
        List(s"${s.name}: revenue" :: years.map(y => cell(s.sales.get(y))),
          s"${s.name}: segment earnings" :: years.map(y => cell(s.earnings.get(y))),
          s"${s.name}: share of segment revenue" :: years.map(y => {
            val total = profile.segments.flatMap(_.sales.get(y)).sum
            s.sales.get(y).filter(_ => total > 0).fold(NotApplicable)(v => Format.percent(v / total))
          }))
      } ++ List("Sum of segment revenue (as reported in the segment note)" :: years.map(y =>
        Format.millions(profile.segments.flatMap(_.sales.get(y)).sum)),
        "Reconciling items to consolidated revenue" :: years.map(y =>
          cell(analysis.record.grid.get(LineItem.Revenue, Period.fiscalYear(y)).map(v => v.value - profile.segments.flatMap(_.sales.get(y)).sum))),
        "Reported consolidated revenue" :: years.map(y => cell(analysis.record.grid.get(LineItem.Revenue, Period.fiscalYear(y)).map(_.value)))),
      Some(s"${profile.concentrationBasis}: ${Format.percent(profile.largestShare.value)}. " +
        s"Commodity-price-linked share of ${analysis.record.periods.lastYear} segment ${SegmentShares.measure(profile.segments, List(analysis.record.periods.lastYear))}: " +
        s"${Format.percent(profile.priceLinkedShare.value)}."))
  }

  private def sources(analysis: CreditAnalysis): MemoTable = {
    val record = analysis.record
    def xbrl(entity: String, grid: GoldenGrid) = grid.values.map { v =>
      List(entity, s"${words(v.item.toString)} (${v.period.label})", Format.millions(v.value), v.source.describe)
    }
    val values = xbrl(record.ticker, record.grid) ++ record.peers.flatMap(p => xbrl(p.ticker, p.grid))
    val inputs = (profileSources(record) ++ record.officers.map(_.source)).map(s => List(record.ticker, "Extracted input", "see source", s.describe)) ++
      record.peers.flatMap(p => p.disclosedRatings.map(r => List(p.ticker, s"${r.house} rating (${r.asOf})", r.rating, r.source.describe)))
    MemoTable("SOURCE APPENDIX", List("Company", "Item", "Value", "Source"), values ++ inputs,
      Some("Each filing cited here is listed with its EDGAR address in the filings table."),
      rowIds = (record.grid.values.map(v => SourceAnchor.id(record.ticker, v.item, v.period)) ++
        record.peers.flatMap(p => p.grid.values.map(v => SourceAnchor.id(p.ticker, v.item, v.period))))
        .zipWithIndex.map((id, index) => index -> id).toMap)
  }

  private def filings(analysis: CreditAnalysis): MemoTable = {
    val record = analysis.record
    def cited(entity: String, cik: Long, sources: List[Source]) = sources.collect {
      case Source.Xbrl(_, form, accession) => (entity, form, accession, filingUrl(cik, accession))
      case Source.Exhibit(form, accession, _, _) => (entity, form, accession, filingUrl(cik, accession))
    }
    val all = cited(record.ticker, record.cik, record.grid.values.map(_.source) ++ profileSources(record) ++ record.officers.map(_.source)) ++
      record.peers.flatMap(p => cited(p.ticker, p.cik, p.grid.values.map(_.source) ++ p.disclosedRatings.map(_.source)))
    MemoTable("FILINGS CITED", List("Company", "Form", "Accession", "EDGAR"), all.distinct.sortBy(f => (f._1 != record.ticker, f._1, f._3))
      .map((entity, form, accession, url) => List(entity, form, accession, url)))
  }

  private def profileSources(record: DealRecord): List[Source] = {
    val profile = record.profile
    (List(profile.commitment.source, profile.availability.source, profile.covenant.threshold.source, profile.largestShare.source,
      profile.priceLinkedShare.source, profile.facility.maturity.source, profile.facility.agent.source) ++
      profile.contractLifeYears.map(_.source).toList ++ profile.agencyRatings.map(_.source) ++ profile.fundedDebt.map(_.source) ++
      profile.annualEbitda.map(_.source) ++ profile.ltmEbitda.map(_.source).toList ++ profile.segments.map(_.source) ++
      profile.proForma.map(_.source).toList).distinct
  }

  private def filingUrl(cik: Long, accession: String): String =
    s"https://www.sec.gov/Archives/edgar/data/$cik/${accession.replace("-", "")}/"

  private def analystInputs(analysis: CreditAnalysis): MemoTable = {
    val inputs = analysis.record.inputs
    def yesNo(value: Boolean) = if (value) "Yes" else "No"
    MemoTable("ANALYST JUDGMENTS", List("Judgment", "Value"), List(
      List("Shareholder support assessment", s"${inputs.supportBasis}. Score ${inputs.support.score}."),
      List("Commodity-price-linked segments", if (inputs.priceLinkedSegments.isEmpty) "None" else inputs.priceLinkedSegments.mkString(", ")),
      List("Commodity modifier", inputs.commodityModifier.fold("Not applied")(c => s"Applied: over 85% of revenue priced off $c")),
      List("Customer over 10% of revenue", yesNo(inputs.customerOverTenPercent)),
      List("Concentration measure", inputs.concentration.fold("Largest segment share (segment note)")(c => s"${c.label} (discovery call)")),
      List("Rating override", inputs.ratingOverride.fold("None")(o =>
        s"One notch ${if (o.notches < 0) "weaker" else "stronger"} (${o.component}); ${o.approvedBy}, ${Format.date(o.date)}")),
      List("Stress scenarios", inputs.stressScenarios.map(_.name).mkString("; ")),
      List("Pro forma event", inputs.proForma.fold("None")(_.event)),
      List("Peer set and peer ratings", inputs.peers.map(p => s"${p.ticker} (${p.ratings.replace("n.p.", Unavailable)})").mkString("; "))
    ), Some("Judgments are distinct from filed facts. Supporting calculations and source records are in the linked audit companion."))
  }

  /** The master scale: each risk rating with its S&P and Moody's equivalents, PD and composite range. */
  private def ratingMap(analysis: CreditAnalysis): MemoTable = {
    val grades = MasterScale.grades
    val rating = analysis.rating
    val external = analysis.record.profile.agencyRatings
    val bold = grades.zipWithIndex.flatMap { (g, row) =>
      List(Option.when(external.exists(a => a.house == "S&P" && a.rating == g.sp))((row, 1)),
        Option.when(external.exists(a => a.house == "Moody's" && a.rating == g.moodys))((row, 2))).flatten
    }.toSet
    MemoTable("RISK RATING MAP: LOGICOV TO S&P AND MOODY'S", List("Risk rating", "S&P", "Moody's", "One-year PD", "Composite", "This memo"),
      grades.zipWithIndex.map { (g, i) =>
        val upper = grades.lift(i + 1).fold("and above")(next => s"to ${Format.decimal(next.compositeFrom - BigDecimal("0.01"))}")
        val marker = List(Option.when(g == rating.grade)("Final"), Option.when(g == rating.machineGrade)("Calculated")).flatten.mkString(", ")
        List(g.label, g.sp, g.moodys, Format.percent(g.pd, 2), s"${Format.decimal(g.compositeFrom)} $upper", if (marker.isEmpty) "" else marker)
      }, Some("Risk Rating Guidance v2.2 master scale. Agency columns are equivalents for comparison; agency ratings are not inputs to the risk rating. Bold agency cells identify the borrower's current external ratings in this memo."), boldCells = bold)
  }

  private def measuredAt(analysis: CreditAnalysis): String = analysis.covenant.covenant.kind match {
    case CovenantKind.MaxNetLeverage => s"Net debt at ${analysis.record.profile.ratingEbitda(analysis.record.periods)._2.label}; ${ebitdaLabel(analysis)}"
    case CovenantKind.MaxDebtToCapitalization => s"Carrying debt and total equity at ${analysis.record.periods.latest.label}"
  }

  private def liquidity(cover: BigDecimal): String =
    if (cover == KeyRatios.NoNearMaturities) "≥5x (no maturities within 12 months)" else Format.multiple(cover)

  private def ebitdaLabel(analysis: CreditAnalysis): String = {
    val profile = analysis.record.profile
    profile.ltmEbitda.fold(s"FY${analysis.record.periods.lastYear} EBITDA")(_ => s"LTM EBITDA at ${analysis.record.periods.latest.label}")
  }

  private def words(identifier: String): String =
    identifier.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase.capitalize

  private def componentSource(analysis: CreditAnalysis, component: RatingComponent): String = {
    val profile = analysis.record.profile
    val ebitda = profile.ltmEbitda.map(_.source).orElse(profile.annualEbitda.headOption.map(_.source)).fold("EBITDA n.p.")(_.describe)
    component match {
      case RatingComponent.LeverageAndCashFlow => s"Debt and cash: Forms 10-K/10-Q XBRL; EBITDA: $ebitda"
      case RatingComponent.CoverageAndLiquidity => s"Interest: Form 10-K XBRL; EBITDA: $ebitda; availability: ${profile.availability.source.describe}"
      case RatingComponent.Scale =>
        if (analysis.ratios.scaleEbitda == analysis.ratios.ebitda) ebitda else profile.annualEbitda.map(_.source.describe).distinct.mkString("3-year average: ", "; ", "")
      case RatingComponent.Diversification => profile.largestShare.source.describe
      case RatingComponent.Industry => profile.priceLinkedShare.source.describe
      case RatingComponent.Support => s"Analyst input: ${analysis.record.inputs.supportBasis}"
    }
  }

  private def row(grid: GoldenGrid, label: String, item: LineItem, periods: List[Period]): List[String] =
    label :: periods.map(p => cell(grid.get(item, p).map(_.value)))

  private def carrying(grid: GoldenGrid, period: Period): Option[BigDecimal] = for {
    current <- grid.get(LineItem.CurrentDebt, period)
    long <- grid.get(LineItem.LongTermDebtCarrying, period)
  } yield current.value + long.value

  private def gross(grid: GoldenGrid, period: Period): Option[BigDecimal] =
    grid.get(LineItem.DebtPrincipal, period).map(_ => KeyRatios.grossDebt(grid, period))

  private def fcf(grid: GoldenGrid, fy: Period): Option[BigDecimal] = for {
    ocf <- grid.get(LineItem.OperatingCashFlow, fy)
    capex <- grid.get(LineItem.CashCapex, fy)
  } yield ocf.value - capex.value

  private def cell(value: Option[BigDecimal]): String = value.fold(NotPulled)(Format.millions)

  private def available(value: Option[BigDecimal]): String = value.fold(Unavailable)(Format.millions)

  /** Drops rows with no value in any period, so a table never prints a line of blanks. */
  private def withValues(rows: List[List[String]]): List[List[String]] = rows.filterNot(_.tail.forall(_ == NotPulled))

  private def background(filed: String, update: Option[String]): String = update.fold(filed) { u =>
    val note = s"Per discovery call: ${u.stripSuffix(".")}."
    if (filed == Unavailable) note else s"$filed $note"
  }

  /** The leading clauses of a filed biography, verbatim, up to about 30 words. */
  private def brief(background: String): String =
    if (background.isEmpty) Unavailable
    else background.split("; ").toList.scanLeft("")((acc, c) => if (acc.isEmpty) c else s"$acc; $c").tail
      .takeWhile(_.split(" ").length <= BriefWords).lastOption.getOrElse(background.split("; ").head)

  /** Lower ranks first: the chief executive, the chair, the CFO, the president, the COO, then other chiefs. */
  private def seniority(title: String): Int = {
    val t = title.toLowerCase
    if (t.contains("chief executive") || t.matches(".*\\bceo\\b.*")) 0
    else if (t.contains("chairman") || t.matches(".*\\bchair\\b.*")) 1
    else if (t.contains("chief financial")) 2
    else if (t.startsWith("president")) 3
    else if (t.contains("chief operating")) 4
    else if (t.contains("chief")) 5
    else 6
  }

  /**
   * The senior officers listed in the filing, with what the discovery call says has changed since: people it names come
   * first, and anyone it names whom the filing does not list (a later appointment) gets a row of their own.
   */
  private def leadership(record: DealRecord): MemoTable = {
    val clauses = record.callFacts.toList.sortBy(_._1).flatMap {
      case (role @ LeadershipRole(_), names) => names.split(OutsideParentheses).map(_.trim).filter(_.nonEmpty).map(role -> _).toList
      case _ => Nil
    }
    def surname(name: String) = name.split(" ").last
    // Match the full name, or first and last name, so relatives who share a surname stay distinct.
    def mentions(clause: String, name: String) = clause.contains(name) || clause.contains(s"${name.split(" ").head} ${surname(name)}")
    def named(name: String) = clauses.find((_, clause) => mentions(clause, name))
    def remainder(name: String, clause: String) =
      FacilityTerms.american(clause.replace(name, "").dropWhile(c => c == ',' || c == ' ' || c == '(').stripSuffix(")").trim)
    if (record.officers.isEmpty) {
      MemoTable("LEADERSHIP", List("Role", "Name"), clauses.map((role, clause) => List(role, clause)), Some("Source: discovery call."))
    } else {
      val filed = record.officers.map { o =>
        val update = named(o.name).map((_, clause) => remainder(o.name, clause)).filter(_.exists(_.isDigit))
        val departed = update.exists(u => u.toLowerCase.contains("retired") || u.toLowerCase.contains("former"))
        (named(o.name).isEmpty, if (departed) Departed else seniority(s"${update.getOrElse("")} ${o.title}")) ->
          List(o.name, o.title, o.age.fold(Unavailable)(_.toString), background(brief(o.background), update))
      }
      val appointed = clauses.filterNot((_, clause) => record.officers.exists(o => mentions(clause, o.name))).map { (role, clause) =>
        val name = clause.takeWhile(c => c != ',' && c != '(').trim
        (false, seniority(role)) -> List(name, role, Unavailable, background(Unavailable, Some(s"$role ${remainder(name, clause)}")))
      }
      MemoTable("LEADERSHIP", List("Name", "Title (filing)", "Age", "Background"),
        (appointed ++ filed).sortBy(_._1).map(_._2).take(TopOfficers),
        Some(record.officers.headOption.fold("")(o => s"Titles, ages and backgrounds as written in the ${o.source.describe.takeWhile(_ != ' ')}. " +
          "Changes management stated on the discovery call are noted in the background; a person with no age was appointed after the filing.")))
    }
  }


  private def notches(n: Int): String = n match {
    case 0 => "Equal"
    case n if n > 0 => s"$n notch${if (n > 1) "es" else ""} more conservative"
    case n => s"${-n} notch${if (n < -1) "es" else ""} stronger"
  }
}
