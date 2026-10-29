package logicov.rating

import fabric.rw.RW

enum Ladder(val leverage: List[BigDecimal], val coverageFactor: BigDecimal) {
  case A extends Ladder(List[BigDecimal](0.5, 1.0, 1.5, 2.0, 3.0, 4.0, 5.0, 6.0), 1)
  case B extends Ladder(List[BigDecimal](2.5, 3.0, 3.5, 4.0, 5.0, 6.0, 7.0, 8.0), 0.6)
}

object Ladder {
  given RW[Ladder] = RW.enumeration(values.toList, _.toString)

  def select(feeBasedShare: BigDecimal, contractLifeYears: BigDecimal, customerOverTenPercent: Boolean): Ladder =
    if (feeBasedShare >= 0.70 && contractLifeYears >= 5 && !customerOverTenPercent) B else A
}
