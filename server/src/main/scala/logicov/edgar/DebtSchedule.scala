package logicov.edgar

import logicov.analysis.FundedDebt
import logicov.facts.CallFacts
import logicov.memo.Format
import logicov.source.Source

/**
 * Funded debt by source: notes from the debt note's per-instrument XBRL, commercial paper and the revolver from the
 * latest filing, and the debt events named in the discovery call.
 */
object DebtSchedule {
  private val Million = BigDecimal(1000000)
  private val InstrumentAxis = "DebtInstrumentAxis"
  private val TypeAxis = "LongtermDebtTypeAxis"
  private val NoteAxes = Set(InstrumentAxis, TypeAxis, "ConsolidatedEntitiesAxis")
  private val ShortTermAxis = "ShortTermDebtTypeAxis"
  private val CreditFacilityAxis = "CreditFacilityAxis"
  private val Tolerance = BigDecimal("0.02")
  private val AnyYear = """\b20\d{2}\b""".r
  private val WordYear = """(?i)due\s+(?:[a-z]+\s+)?twenty[\s-]+(twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety)(?:[\s-]+(one|two|three|four|five|six|seven|eight|nine))?\b""".r
  private val Tens = List("twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
  private val Units = List("one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
  private val CallDebtField = """(?i).*\b(notes|issuance|term loan|securitization|preferred)\b.*""".r

  def rows(interim: XbrlInstance, annual: XbrlInstance, call: CallFacts): List[FundedDebt] = {
    val notesFiling = if (complete(interim)) interim else annual
    notesByType(notesFiling) ++ commercialPaper(interim).toList ++ List(revolver(interim, call)) ++ callEvents(call)
  }

  private def notes(filing: XbrlInstance): List[InstanceFact] = filing.facts.filter { f =>
    f.concept == "DebtInstrumentCarryingAmount" && f.isUsd && f.start.isEmpty && f.end == filing.periodEnd &&
      f.dimensions.contains(InstrumentAxis) && f.dimensions.keySet.subsetOf(NoteAxes) && f.amount > 0
  }

  /** The per-instrument schedule is complete when it adds up to the filing's own total within 2%. */
  private def complete(filing: XbrlInstance): Boolean =
    filing.amount("DebtInstrumentCarryingAmount", None, filing.periodEnd).exists { total =>
      total > 0 && ((notes(filing).map(_.amount).sum - total).abs / total) <= Tolerance
    }

  private def notesByType(filing: XbrlInstance): List[FundedDebt] = {
    val asOf = Format.date(filing.periodEnd)
    val byType = notes(filing).groupBy(_.dimensions.get(TypeAxis).map(filing.label).getOrElse("Other debt instruments"))
    byType.toList.sortBy(-_._2.map(_.amount).sum).map {
      case (kind, facts) =>
        val instruments = facts.map(_.dimensions(InstrumentAxis)).distinct
        val coupons = instruments.flatMap(i => filing.facts.find(f => f.concept == "DebtInstrumentInterestRateStatedPercentage" &&
          f.dimensions.get(InstrumentAxis).contains(i)).map(_.amount)).filter(_ > 0).sorted
        val years = instruments.flatMap(i => maturityYear(filing.label(i))).filter(_ >= filing.periodEnd.getYear)
        val terms = ((if (instruments.size == 1) "1 instrument" else s"${instruments.size} instruments") ::
          span("coupon", "coupons", coupons.map(c => Format.percent(c, 3))) ++ span("maturity", "maturities", years.sorted.map(_.toString))).mkString("; ")
        FundedDebt(kind, terms, s"${Format.millions(facts.map(_.amount).sum / Million)} outstanding at $asOf (reported instrument amounts)",
          Source.Xbrl(s"DebtInstrumentCarryingAmount by instrument ($kind)", filing.form, filing.accession))
    }
  }

  /** The final maturity named in an instrument's label; "Due 2026 Through 2035" is 2035. */
  private def maturityYear(label: String): Option[Int] = {
    val due = label.toLowerCase.indexOf("due")
    Option.when(due >= 0)(AnyYear.findAllIn(label.substring(due)).map(_.toInt).toList).filter(_.nonEmpty).map(_.max)
  }.orElse {
    WordYear.findFirstMatchIn(label).map { m =>
      2000 + (Tens.indexOf(m.group(1).toLowerCase) + 2) * 10 + Option(m.group(2)).fold(0)(u => Units.indexOf(u.toLowerCase) + 1)
    }
  }

  private def span(one: String, many: String, sorted: List[String]): List[String] = sorted.distinct match {
    case Nil => Nil
    case single :: Nil => List(s"$one $single")
    case values => List(s"$many ${values.head} to ${values.last}")
  }

  private def commercialPaper(interim: XbrlInstance): Option[FundedDebt] = {
    val end = interim.periodEnd
    List("CommercialPaper", "ShortTermBorrowings", "DebtCurrent").view.flatMap { concept =>
      interim.amount(concept, None, end, Map(ShortTermAxis -> "CommercialPaperMember")).map(concept -> _)
    }.headOption.map { (concept, amount) =>
      FundedDebt("Commercial paper", "Backstopped by the revolver", s"${Format.millions(amount / Million)} at ${Format.date(end)}",
        Source.Xbrl(s"$concept (commercial paper)", interim.form, interim.accession))
    }
  }

  private def revolver(interim: XbrlInstance, call: CallFacts): FundedDebt = {
    val end = interim.periodEnd
    val facility = interim.facts.filter(f => f.start.isEmpty && f.end == end && f.isUsd && f.dimensions.keySet == Set(CreditFacilityAxis))
    def tagged(concept: String) = facility.find(_.concept == concept).map(f => Format.millions(f.amount / Million))
    val parts = List(
      tagged("LineOfCreditFacilityMaximumBorrowingCapacity").map(v => s"capacity $v"),
      tagged("LettersOfCreditOutstandingAmount").map(v => s"letters of credit $v"),
      tagged("LineOfCreditFacilityRemainingBorrowingCapacity").map(v => s"available $v")
    ).flatten
    if (parts.isEmpty) {
      FundedDebt("Revolving credit facility", call("Existing facility"), call("Drawn status"), Source.CallScript("Drawn status"))
    } else {
      FundedDebt("Revolving credit facility", call("Existing facility"), s"${call("Drawn status")}; at ${Format.date(end)} ${parts.mkString(", ")}",
        Source.Xbrl("LineOfCreditFacility capacity and letters of credit", interim.form, interim.accession))
    }
  }

  private def callEvents(call: CallFacts): List[FundedDebt] = call.fields.toList.sortBy(_._1).collect {
    case (field @ CallDebtField(_), value) => FundedDebt(field, value, "Per management", Source.CallScript(field))
  }
}
