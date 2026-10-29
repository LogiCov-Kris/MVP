package logicov.auth

import lightdb.id.Id
import logicov.db.DB
import logicov.model.SessionView
import rapid.Task

import java.util.Locale

object AuthService {
  val SessionSeconds: Long = 12 * 60 * 60

  def email(value: String): String = value.trim.toLowerCase(Locale.ROOT)

  def signIn(address: String, password: String): Task[Option[String]] = {
    if (address.length > 254 || password.isEmpty || password.length > 256) Task.pure(None)
    else DB.users.transaction(_.get(Id[User](SessionTokens.digest(email(address))))).flatMap { user =>
      Task(Passwords.verify(password, user.map(_.passwordHash).getOrElse(Passwords.dummy))).flatMap { valid =>
        user.filter(u => valid && u.active) match {
          case None => Task.pure(None)
          case Some(u) => createSession(u)
        }
      }
    }
  }

  private[auth] def createSession(user: User): Task[Option[String]] = {
    if (!user.active) Task.pure(None)
    else DB.tenants.transaction(_.get(user.tenantId)).flatMap {
      case Some(t) if t.active =>
        val token = SessionTokens.create()
        val session = Session(user._id, System.currentTimeMillis() + SessionSeconds * 1000,
          _id = Id[Session](SessionTokens.digest(token)), credentialHash = SessionTokens.digest(user.passwordHash))
        DB.sessions.transaction(_.insert(session)).map(_ => Some(token))
      case _ => Task.pure(None)
    }
  }

  def current(token: String): Task[Option[SessionView]] = {
    if (!token.matches("[A-Za-z0-9_-]{43}")) Task.pure(None)
    else DB.sessions.transaction(_.get(Id[Session](SessionTokens.digest(token)))).flatMap {
      case Some(s) if s.expiresAt > System.currentTimeMillis() => DB.users.transaction(_.get(s.userId)).flatMap {
        case Some(u) if u.active && s.credentialHash == SessionTokens.digest(u.passwordHash) => DB.tenants.transaction(_.get(u.tenantId)).map {
          case Some(t) if t.active => Some(SessionView(u.email, t._id.value, t.name, u.role))
          case _ => None
        }
        case _ => Task.pure(None)
      }
      case _ => Task.pure(None)
    }
  }

  private[auth] def resetPassword(user: User, password: String): Task[Boolean] = Task.defer {
    val hash = Passwords.hash(password)
    DB.users.transaction { users =>
      val statement = users.state.openConnection().prepareStatement(s"SELECT _id FROM ${users.store.fqn} WHERE _id = ? FOR UPDATE")
      try {
        statement.setString(1, user._id.value)
        statement.executeQuery().close()
      } finally statement.close()
      users.get(user._id).flatMap {
        case Some(current) if current.active && current.passwordHash == user.passwordHash && current.created == user.created =>
          users.upsert(current.copy(passwordHash = hash)).map(_ => true)
        case _ => Task.pure(false)
      }
    }
  }

  def signOut(token: String): Task[Unit] =
    DB.sessions.transaction(_.delete(Id[Session](SessionTokens.digest(token)))).unit

  private def newUser(tenantId: Id[Tenant], address: String, password: String): User = {
    val normalized = email(address)
    require(normalized.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+") && normalized.length <= 254, "Valid email required")
    User(tenantId, normalized, Passwords.hash(password), _id = Id[User](SessionTokens.digest(normalized)))
  }

  def addUser(tenantId: String, address: String, password: String): Task[User] = Task.defer {
    val user = newUser(Id[Tenant](tenantId), address, password)
    DB.users.transaction { users =>
      users.get(user._id).flatMap { existing =>
        require(existing.isEmpty, "Account already exists; provisioning does not reset passwords")
        DB.tenants.transaction.joining(users)(_.get(user.tenantId)).flatMap { tenant =>
          require(tenant.exists(_.active), "An active tenant is required")
          users.insert(user)
        }
      }
    }
  }

  def provision(tenantName: String, address: String, password: String): Task[User] = Task.defer {
    require(tenantName.trim.nonEmpty && tenantName.length <= 120, "Tenant name required (up to 120 characters)")
    val tenant = Tenant(tenantName.trim)
    val user = newUser(tenant._id, address, password)
    DB.users.transaction { users =>
      users.get(user._id).flatMap { existing =>
        require(existing.isEmpty, "Account already exists; provisioning does not reset passwords")
        DB.tenants.transaction.joining(users)(_.insert(tenant)).flatMap(_ => users.insert(user))
      }
    }
  }
}
