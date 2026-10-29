package logicov.auth

import fabric.rw.*
import lightdb.doc.{JsonConversion, RecordDocument, RecordDocumentModel}
import lightdb.id.Id
import lightdb.time.Timestamp

case class Session(userId: Id[User], expiresAt: Long,
                  created: Timestamp = Timestamp(),
                  modified: Timestamp = Timestamp(),
                  _id: Id[Session] = Session.id(), credentialHash: String = "") extends RecordDocument[Session]

object Session extends RecordDocumentModel[Session] with JsonConversion[Session] {
  override implicit val rw: RW[Session] = RW.gen
}
