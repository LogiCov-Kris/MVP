package logicov.auth

import fabric.rw.*
import lightdb.doc.{JsonConversion, RecordDocument, RecordDocumentModel}
import lightdb.id.Id
import lightdb.time.Timestamp
import logicov.model.UserRole

case class User(tenantId: Id[Tenant], email: String, passwordHash: String, active: Boolean = true, role: UserRole = UserRole.Analyst,
                  created: Timestamp = Timestamp(),
                  modified: Timestamp = Timestamp(),
                  _id: Id[User] = User.id()) extends RecordDocument[User]

object User extends RecordDocumentModel[User] with JsonConversion[User] {
  override implicit val rw: RW[User] = RW.gen
  val email: I[String] = field.index(_.email)
}
