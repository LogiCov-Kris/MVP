package logicov.edgar

import logicov.analysis.Segment
import logicov.source.Source

import java.time.LocalDate

/**
 * Reads segment revenue and the Company's own segment earnings measure from the 10-K segment note.
 */
object SegmentExtractor {
  private val Million = BigDecimal(1000000)
  private val SegmentAxis = "StatementBusinessSegmentsAxis"
  private val ConsolidationAxis = "ConsolidationItemsAxis"
  private val SegmentTotals = List("OperatingSegmentsMember", "OtherOperatingSegmentsAndIntersegmentEliminationsMember")
  private val RevenueConcepts = List("Revenues", "RevenueFromContractWithCustomerExcludingAssessedTax")
  private val EarningsConcepts = List(
    "ProfitLoss",
    "OperatingIncomeLoss",
    "IncomeLossFromContinuingOperationsBeforeIncomeTaxesExtraordinaryItemsNoncontrollingInterest"
  )

  def extract(annual: XbrlInstance, years: List[Int], priceLinked: List[String], end: Option[LocalDate] = None): List[Segment] = {
    val members = annual.facts.flatMap(_.dimensions.get(SegmentAxis)).distinct
    val found = for {
      (revenueConcept, revenue) <- measure(annual, RevenueConcepts, members, years, end)
      (earningsConcept, earnings) <- measure(annual, EarningsConcepts, members, years, end)
    } yield {
      val source = Source.Xbrl(s"$revenueConcept and $earningsConcept by segment", annual.form, annual.accession)
      revenue.keys.toList.filter(earnings.contains).sortBy(m => -revenue(m)(years.last)).map { member =>
        val name = readable(annual.label(member))
        Segment(name, revenue(member), earnings(member), priceLinked.contains(name), source)
      }
    }
    found.getOrElse(Nil)
  }

  private def measure(annual: XbrlInstance, concepts: List[String], members: List[String],
                      years: List[Int], end: Option[LocalDate]): Option[(String, Map[String, Map[Int, BigDecimal]])] = {
    val candidates = for {
      concept <- concepts.view
      total <- SegmentTotals.view
    } yield concept -> members.flatMap { member =>
      val byYear = years.flatMap { year =>
        annual.amount(concept, Some(LocalDate.of(year, 1, 1)), end.getOrElse(LocalDate.of(year, 12, 31)),
          Map(SegmentAxis -> member, ConsolidationAxis -> total)).map(year -> _ / Million)
      }
      if (byYear.size == years.size) Some(member -> byYear.toMap) else None
    }.toMap
    candidates.find(_._2.size > 1)
  }

  private val Connectors = Set("and", "of", "the", "for", "in")

  /** "Oil And Gas Segment" becomes "Oil and Gas": filer labels capitalize every word and often append "Segment". */
  def readable(label: String): String = label.stripSuffix(" Segment").split(" ").toList match {
    case first :: rest => (first :: rest.map(w => if (Connectors.contains(w.toLowerCase)) w.toLowerCase else w)).mkString(" ")
    case Nil => label
  }
}
