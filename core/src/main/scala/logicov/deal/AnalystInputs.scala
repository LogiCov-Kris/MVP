package logicov.deal

import fabric.rw.*
import logicov.analysis.{ProForma, StressScenario}
import logicov.rating.{RatingOverride, Support}

/**
 * The judgments an analyst makes for a deal. Everything else in the record is extracted from the source pack.
 */
case class AnalystInputs(ticker: String,
                         cik: Long,
                         support: Support,
                         supportBasis: String,
                         priceLinkedSegments: List[String],
                         commodityModifier: Option[String],
                         customerOverTenPercent: Boolean,
                         concentration: Option[CallReference],
                         ebitda: EbitdaBasis,
                         covenantIndicative: Boolean,
                         ratingOverride: Option[RatingOverride],
                         stressScenarios: List[StressScenario],
                         proForma: Option[ProForma],
                         peers: List[PeerInput],
                         ratingReview: Option[String] = None,
                         narrativeNotes: Map[String, String] = Map.empty,
                         agencyUpdates: List[AgencyUpdate] = Nil) derives RW
