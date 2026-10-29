package logicov.workspace

import fabric.rw.*
import logicov.analysis.{CovenantKind, FacilityAction}
import logicov.rating.Support

case class CreditSetupRequest(revision: Long, identifier: String, year: Int,
                              facilityKind: String, action: FacilityAction, maturity: String, agent: String,
                              commitment: BigDecimal, availability: BigDecimal, covenantKind: CovenantKind,
                              covenantThreshold: BigDecimal, annualEbitda: List[BigDecimal],
                              largestShare: BigDecimal, priceLinkedShare: BigDecimal, concentrationBasis: String,
                              support: Support, supportBasis: String, customerOverTenPercent: Boolean,
                              documentId: String, citation: String, reason: String) derives RW
