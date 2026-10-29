package logicov.rating

import fabric.rw.RW

enum Support(val score: Int, val label: String) {
  case GuaranteeAA extends Support(1, "written guarantee from an AA− or better obligor")
  case GuaranteeA extends Support(2, "written guarantee from an A− or better obligor")
  case ControllingHolder extends Support(3, "no guarantee; holder with 50% or more of voting control or 20% or more of total equity")
  case MinorityHolder extends Support(4, "no guarantee; holder with 10% to 20% of total equity")
  case Neutral extends Support(5, "no parent, sponsor or identifiable holder; neutral, not a penalty")
  case CashExtraction extends Support(6, "cash extraction by a holder")
}

object Support {
  given RW[Support] = RW.enumeration(values.toList, _.toString)
}
