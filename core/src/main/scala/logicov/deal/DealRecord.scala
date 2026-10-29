package logicov.deal

import fabric.rw.*
import logicov.analysis.{CreditProfile, Segment, SpreadPeriods}
import logicov.grid.{GoldenGrid, GridRevision}

case class DealRecord(borrower: String,
                      ticker: String,
                      cik: Long,
                      periods: SpreadPeriods,
                      grid: GoldenGrid,
                      profile: CreditProfile,
                      callFacts: Map[String, String],
                      peers: List[Peer],
                      inputs: AnalystInputs,
                      contextDocuments: List[ContextDocument] = Nil,
                      interimSegments: List[Segment] = Nil,
                      officers: List[ExecutiveOfficer] = Nil,
                      revisions: List[GridRevision] = Nil) derives RW
