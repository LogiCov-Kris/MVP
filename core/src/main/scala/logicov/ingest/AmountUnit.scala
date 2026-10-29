package logicov.ingest

import fabric.rw.*

enum AmountUnit(val divisor: BigDecimal) derives RW {
  case Dollars extends AmountUnit(BigDecimal(1000000))
  case Thousands extends AmountUnit(BigDecimal(1000))
  case Millions extends AmountUnit(BigDecimal(1))
}
