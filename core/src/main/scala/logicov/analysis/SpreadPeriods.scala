package logicov.analysis

import fabric.rw.*
import logicov.grid.Period

case class SpreadPeriods(fiscalYears: List[Int], interim: Period, latest: Period) derives RW {
  def lastYear: Int = fiscalYears.last

  def all: List[Period] = fiscalYears.map(Period.fiscalYear) ++ fiscalYears.map(Period.yearEnd) ++ List(interim, latest)
}
