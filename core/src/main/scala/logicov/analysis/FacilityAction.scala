package logicov.analysis

import fabric.rw.RW

enum FacilityAction(val label: String) {
  case Hold extends FacilityAction("Hold existing commitment")
  case AmendAndExtend extends FacilityAction("Amend and extend")
  case New extends FacilityAction("New commitment")
  case Increase extends FacilityAction("Increase")
  case Decline extends FacilityAction("Decline")
}

object FacilityAction {
  given RW[FacilityAction] = RW.enumeration(values.toList, _.toString)
}
