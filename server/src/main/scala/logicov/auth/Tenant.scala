package logicov.auth

import fabric.rw.*
import lightdb.doc.{JsonConversion, RecordDocument, RecordDocumentModel}
import lightdb.id.Id
import lightdb.time.Timestamp
import logicov.model.WorkspaceAudit

case class Tenant(name: String, active: Boolean = true,
                  created: Timestamp = Timestamp(),
                  modified: Timestamp = Timestamp(),
                  _id: Id[Tenant] = Tenant.id(), adminRevision: Long = 0,
                  adminHistory: List[WorkspaceAudit] = Nil) extends RecordDocument[Tenant]

object Tenant extends RecordDocumentModel[Tenant] with JsonConversion[Tenant] {
  override implicit val rw: RW[Tenant] = RW.gen
}
