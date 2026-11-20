package logicov.workspace

import fabric.rw.*

enum ProfileField derives RW {
  case Identifier, Year, Ebitda, FacilityKind, Action, Maturity, Agent, Commitment, Availability
  case Covenant, Threshold, Concentration, ConcentrationBasis, PriceLinked, Customer, Support, SupportBasis
  case Document, Citation, Reason
}
