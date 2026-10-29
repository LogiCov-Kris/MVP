package logicov.analysis

import fabric.rw.RW

enum CovenantKind {
  case MaxNetLeverage
  case MaxDebtToCapitalization
}

object CovenantKind {
  given RW[CovenantKind] = RW.enumeration(values.toList, _.toString)
}
