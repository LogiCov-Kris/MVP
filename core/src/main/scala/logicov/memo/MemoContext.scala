package logicov.memo

import logicov.analysis.{CovenantKind, CreditAnalysis, FacilityAction}
import logicov.rating.{AdjacentNotch, Grade, MasterScale, NotchPath, RatingResult}

import java.time.LocalDate
import java.time.format.DateTimeFormatter

case class MemoContext(values: Map[String, String],
                       tables: Map[String, MemoTable],
                       narratives: Map[String, NarrativeText] = Map.empty) {
  def value(key: String): String = values.getOrElse(key, throw new NoSuchElementException(s"Memo template references unknown value '$key'"))

  def table(dataset: String): MemoTable = tables.getOrElse(dataset, throw new NoSuchElementException(s"Memo template references unknown table '$dataset'"))

  /** Everything a narrative may draw on: the record's values and tables plus the retained source documents. */
  def facts: String = render(values)

  /** The record alone, without source documents: the basis for checking a narrative's figures. */
  def recordFacts: String = render(values.filterNot(_._1.startsWith(MemoContext.SourceDocument)))

  private def render(scalars: Map[String, String]): String = {
    val lines = scalars.toList.sortBy(_._1).map((key, value) => s"$key: $value")
    val tabular = tables.filterNot((key, _) => MemoContext.AppendixTables.contains(key)).values.toList.sortBy(_.title).map { t =>
      (t.title :: t.headers.mkString(" | ") :: t.rows.map(_.mkString(" | ")) ++ t.note.toList).mkString("\n")
    }
    (lines.mkString("\n") :: tabular).mkString("\n\n")
  }
}

object MemoContext {
  val SourceDocument = "source-document."
  private val AppendixTables = Set("sources", "filings", "analyst-inputs", "scorecard-sources")

  private val Display = DateTimeFormatter.ofPattern("d MMMM yyyy")

  def build(analysis: CreditAnalysis, memoDate: LocalDate, reference: String): MemoContext = {
    val record = analysis.record
    val profile = record.profile
    val facility = profile.facility
    val rating = analysis.rating
    val ratios = analysis.ratios
    val covenant = analysis.covenant
    val investmentGrade = MasterScale.notch(rating.grade) <= MasterScale.notch("BBB-")
    val values = Map(
      "borrower" -> record.borrower,
      "rating.review" -> record.inputs.ratingReview.getOrElse(""),
      "source.audit" -> s"${record.ticker.toLowerCase}-audit.html",
      "facility.pricing" -> record.callFacts.get("Existing pricing grid").orElse(record.callFacts.get("Drawn margin"))
        .orElse(record.callFacts.get("Pricing")).fold("The applicable pricing-grid step has not been extracted or verified; this is not a statement that pricing is unpublished.")(p =>
          s"Existing terms per management: $p. Confirm the applicable ratings step in the executed agreement before closing."),
      "ticker" -> record.ticker,
      "date" -> memoDate.format(Display),
      "reference" -> reference,
      "recommendation" -> (if (record.inputs.ratingReview.isDefined) "Defer final approval pending rating review"
        else if (investmentGrade) "Recommended for approval" else "Recommended for approval with conditions"),
      "facility.amount" -> Format.billions(profile.commitment.value),
      "facility.kind" -> facility.kind,
      "facility.summary" -> s"${Format.billions(profile.commitment.value)} ${facility.kind} to ${facility.maturity.value}",
      "facility.action" -> facility.action.label,
      "facility.recommendation" -> {
        val subject = s"${Format.billions(profile.commitment.value)} ${facility.kind} to ${facility.maturity.value}"
        facility.action match {
          case FacilityAction.Hold => s"hold the existing $subject"
          case FacilityAction.AmendAndExtend => s"amend and extend the $subject"
          case FacilityAction.Increase => s"increase the $subject"
          case FacilityAction.New => s"commit to the $subject"
          case FacilityAction.Decline => s"decline the $subject"
        }
      },
      "facility.maturity" -> facility.maturity.value,
      "facility.agent" -> facility.agent.value,
      "rating.grade" -> rating.grade.label,
      "rating.internal" -> s"Final: ${rating.grade.label}\nCalculated: ${rating.machineGrade.label}",
      "rating.mapping" -> rating.grade.mapping,
      "rating.calculated" -> rating.machineGrade.mapping,
      "rating.cover" -> (s"Final: ${rating.grade.mapping} (Calculated: ${rating.machineGrade.mapping})\n" +
        profile.agencyRatings.map(a => s"${a.house} ${a.rating}").mkString(" / ")),
      "rating.composite" -> Format.decimal(rating.composite),
      "rating.class" -> (if (investmentGrade) "investment grade" else "non-investment grade"),
      "rating.pd" -> Format.percent(rating.grade.pd, 2),
      "rating.lgd" -> Format.percent(rating.lgd, 0),
      "rating.ead" -> Format.billions(rating.exposure),
      "rating.el" -> s"$$${Format.decimal(rating.expectedLoss)} million",
      "rating.override" -> rating.ratingOverride.fold("None applied. The risk rating is the rating calculated from the composite.") { o =>
        s"${o.approvedBy}, ${Format.date(o.date)}: one notch ${if (o.notches < 0) "weaker" else "stronger"} (${o.component}). ${o.reason}. " +
          s"Calculated risk rating ${rating.machineGrade.mapping}; final risk rating ${rating.grade.mapping}. The composite is not edited."
      },
      "rating.differential" -> differential(rating, profile.agencyRatings.map(a => a.house -> a.rating)),
      "rating.adjacent" -> adjacent(rating),
      "agencies" -> profile.agencyRatings.map(a => s"${a.house} ${a.rating}").mkString(" / "),
      "agencies.short" -> profile.agencyRatings.map(_.rating).mkString(" / "),
      "rating.outlook" -> internalOutlook(profile.agencyRatings.map(_.outlook)),
      "agencies.median" -> medianHouse(profile.agencyRatings.map(_.rating)).fold("unrated")(g => s"${g.sp} / ${g.moodys}"),
      "ratios.netDebt" -> Format.millions(ratios.netDebt),
      "ratios.ebitda" -> Format.millions(ratios.ebitda),
      "ratios.netLeverage" -> Format.multiple(ratios.netLeverage),
      "ratios.interestCoverage" -> Format.multiple(ratios.interestCoverage),
      "ratios.liquidityCover" -> Format.multiple(ratios.liquidityCover),
      "ratios.fcfToDebt" -> Format.percent(ratios.fcfToDebt),
      "ratios.debtToCapitalization" -> Format.percent(ratios.debtToCapitalization),
      "covenant.description" -> (covenantDescription(covenant.covenant.kind, covenant.threshold) +
        (if (covenant.covenant.indicative) " (indicative proxy; agreement threshold unverified)" else "")),
      "covenant.actual" -> covenantValue(covenant.covenant.kind, covenant.actual),
      "covenant.headroom" -> covenantValue(covenant.covenant.kind, covenant.headroom),
      "covenant.basis" -> (if (covenant.covenant.indicative) "indicative threshold; the agreement level was not in the file"
        else "threshold per management on the discovery call, to be tied to the credit agreement"),
      "facility.feeNote" -> (facility.action match {
        case FacilityAction.Hold => "No fee is proposed for holding the existing commitment."
        case FacilityAction.AmendAndExtend => "No amendment or extension fee is set in the file, so none is proposed here."
        case _ => "No fee is set in the file, so none is proposed here."
      })
    ) ++ record.callFacts.map((field, value) => s"call.$field" -> value) ++
      record.contextDocuments.map(d => s"$SourceDocument${d.name}" -> d.text)
    MemoContext(values, MemoTables.build(analysis))
  }

  def covenantDescription(kind: CovenantKind, threshold: BigDecimal): String = kind match {
    case CovenantKind.MaxNetLeverage => s"Net debt / EBITDA ≤ ${Format.multiple(threshold)}"
    case CovenantKind.MaxDebtToCapitalization => s"Total debt / total capitalization ≤ ${Format.percent(threshold, 0)}"
  }

  def covenantValue(kind: CovenantKind, value: BigDecimal): String = kind match {
    case CovenantKind.MaxNetLeverage => Format.multiple(value)
    case CovenantKind.MaxDebtToCapitalization => Format.percent(value)
  }

  /** Why the calculated risk rating is not the adjacent notch, as the ratio anchors that would have to move. */
  private def adjacent(rating: RatingResult): String = {
    val calculated = rating.machineGrade
    def path(p: NotchPath, stronger: Boolean): String = {
      val moves = p.moves.map(_.description).mkString(" together with ")
      val scores = p.changed.map(c => s"${c.component.label} ${c.score}").mkString(" and ")
      s"It needs a composite of ${Format.decimal(p.threshold)} or ${if (stronger) "lower" else "higher"}: $moves would score $scores " +
        s"and give ${Format.decimal(p.composite)}."
    }
    val stronger = AdjacentNotch.stronger(rating.inputs).map(p => s"Why not ${p.target.mapping}? ${path(p, stronger = true)}")
    val weaker = AdjacentNotch.weaker(rating.inputs).map { p =>
      val question = rating.ratingOverride.fold(s"Why not ${p.target.mapping}?")(_ =>
        s"Why is the calculated risk rating ${calculated.label} and not ${p.target.label}?")
      val close = rating.ratingOverride.fold("")(_ => s" That is not the case, so the final ${rating.grade.label} is an override, not a calculated result.")
      s"$question ${path(p, stronger = false)}$close"
    }
    (stronger.toList ++ weaker.toList).mkString(" ")
  }

  /** Notches versus each house and the median (Risk Rating Guidance v2.2, Section 5). */
  private def differential(rating: RatingResult, houses: List[(String, String)]): String = {
    // notchesVersus is internal minus agency on the master scale, where a lower index is stronger.
    def notches(n: Int) = n match {
      case 0 => "level with"
      case n if n < 0 => s"${-n} notch${if (n < -1) "es" else ""} stronger than"
      case n => s"$n notch${if (n > 1) "es" else ""} more conservative than"
    }
    val perHouse = houses.map((house, agency) => s"${notches(rating.notchesVersus(agency))} $house $agency").mkString("; ")
    medianHouse(houses.map(_._2)).fold("The borrower is unrated, so there is no differential to explain.") { median =>
      val versusMedian = MasterScale.notch(median) - MasterScale.notch(rating.grade)
      val signOff = if (versusMedian.abs > 2) " The difference from the median house exceeds two notches, so credit manager sign-off is required before release." else ""
      s"The final risk rating ${rating.grade.mapping} is $perHouse. The median house is ${median.sp} / ${median.moodys}.$signOff"
    }
  }

  def internalOutlook(houses: List[String]): String = {
    val negative = houses.count(_ == "Negative")
    if (negative >= 2) "Watchful"
    else if (houses.contains("Positive") && negative == 0) "Positive"
    else "Stable"
  }

  private def medianHouse(ratings: List[String]): Option[Grade] =
    if (ratings.isEmpty) None else Some(MasterScale.grades(ratings.map(MasterScale.notch).sorted.apply(ratings.size / 2)))
}
