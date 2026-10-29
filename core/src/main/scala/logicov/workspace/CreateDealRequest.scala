package logicov.workspace

import fabric.rw.*
import logicov.deal.DealRecord
import logicov.ingest.ImportRequest

case class CreateDealRequest(title: String, upload: Option[ImportRequest] = None, record: Option[DealRecord] = None) derives RW
