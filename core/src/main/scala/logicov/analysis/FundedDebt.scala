package logicov.analysis

import fabric.rw.*
import logicov.source.Source

case class FundedDebt(instrument: String, terms: String, status: String, source: Source) derives RW
