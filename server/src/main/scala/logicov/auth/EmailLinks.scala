package logicov.auth

import lightdb.id.Id
import logicov.db.DB
import rapid.Task

class EmailLinks(send: (String, String) => Unit, origin: String, now: () => Long = () => System.currentTimeMillis(),
                 purpose: EmailPurpose = EmailPurpose.SignIn) {
  require(origin.matches("https://[^/#?]+") || origin.matches("http://(127\\.0\\.0\\.1|localhost):[0-9]+"), "Email sign-in requires a trusted application origin")

  def request(address: String): Task[Unit] = Task.defer {
    val email = AuthService.email(address)
    require(email.length <= 254 && email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"), "Enter a valid email address.")
    val key = SessionTokens.digest(email)
    if (!allow(key)) Task.unit
    else DB.users.transaction(_.get(Id[User](key))).flatMap {
      case Some(user) if user.active => DB.tenants.transaction(_.get(user.tenantId)).flatMap {
        case Some(tenant) if tenant.active => Task {
          val token = SessionTokens.create()
          val digest = SessionTokens.digest(token)
          DB.connections.jdbc { connection =>
            val statement = connection.prepareStatement("INSERT INTO email_signin_tokens (digest, user_id, expires_at, identity_hash, purpose) VALUES (?, ?, ?, ?, ?)")
            try {
              statement.setString(1, digest)
              statement.setString(2, key)
              statement.setLong(3, now() + 10 * 60 * 1000)
              statement.setString(4, identity(user))
              statement.setString(5, purpose.toString)
              statement.executeUpdate()
            } finally statement.close()
          }
          val fragment = if (purpose == EmailPurpose.Reset) "reset" else "signin"
          try send(email, s"$origin/#$fragment=$token")
          catch {
            case cause: Exception =>
              remove(digest)
              throw new IllegalStateException("Sign-in email delivery failed", cause)
          }
        }
        case _ => Task.unit
      }
      case _ => Task.unit
    }
  }

  def confirm(address: String, token: String): Task[Option[String]] = {
    require(purpose == EmailPurpose.SignIn, "A sign-in link is required")
    consume(address, token).flatMap {
      case Some(user) => AuthService.createSession(user)
      case None => Task.pure(None)
    }
  }

  def reset(address: String, token: String, password: String): Task[Boolean] = Task.defer {
    require(purpose == EmailPurpose.Reset, "A reset link is required")
    require(password.length >= 14 && password.length <= 256, "Passwords must contain 14 to 256 characters")
    consume(address, token).flatMap {
      case Some(user) => AuthService.resetPassword(user, password)
      case None => Task.pure(false)
    }
  }

  private def consume(address: String, token: String): Task[Option[User]] = Task.defer {
    if (!token.matches("[A-Za-z0-9_-]{43}") || address.length > 254) Task.pure(None)
    else {
      val key = SessionTokens.digest(AuthService.email(address))
      val consumed = DB.connections.jdbc { connection =>
        val statement = connection.prepareStatement("DELETE FROM email_signin_tokens WHERE digest = ? AND user_id = ? AND expires_at > ? AND purpose = ? RETURNING identity_hash")
        try {
          statement.setString(1, SessionTokens.digest(token))
          statement.setString(2, key)
          statement.setLong(3, now())
          statement.setString(4, purpose.toString)
          val result = statement.executeQuery()
          try { if (result.next()) Some(result.getString(1)) else None } finally result.close()
        } finally statement.close()
      }
      if (consumed.isEmpty) Task.pure(None)
      else DB.users.transaction(_.get(Id[User](key))).flatMap {
        case Some(user) if user.active && consumed.contains(identity(user)) =>
          DB.tenants.transaction(_.get(user.tenantId)).map {
            case Some(tenant) if tenant.active => Some(user)
            case _ => None
          }
        case _ => Task.pure(None)
      }
    }
  }

  private def identity(user: User): String = SessionTokens.digest(user.passwordHash + ":" + user.created.toString)

  private def remove(digest: String): Unit = DB.connections.jdbc { connection =>
    val statement = connection.prepareStatement("DELETE FROM email_signin_tokens WHERE digest = ?")
    try { statement.setString(1, digest); statement.executeUpdate(); () }
    finally statement.close()
  }

  private def allow(key: String): Boolean = DB.connections.jdbc { connection =>
    val lock = connection.createStatement()
    try lock.execute("SELECT pg_advisory_xact_lock(1845249092)") finally lock.close()
    val cutoff = now() - 60 * 60 * 1000
    val cleanup = connection.prepareStatement("DELETE FROM email_signin_requests WHERE requested_at <= ?")
    try { cleanup.setLong(1, cutoff); cleanup.executeUpdate() } finally cleanup.close()
    val expired = connection.prepareStatement("DELETE FROM email_signin_tokens WHERE expires_at <= ?")
    try { expired.setLong(1, now()); expired.executeUpdate() } finally expired.close()
    val counts = connection.prepareStatement("SELECT count(*), count(*) FILTER (WHERE email_hash = ?), max(requested_at) FILTER (WHERE email_hash = ?) FROM email_signin_requests")
    val permitted = try {
      counts.setString(1, key)
      counts.setString(2, key)
      val rows = counts.executeQuery()
      try {
        rows.next()
        rows.getLong(1) < 100 && rows.getLong(2) < 5 && rows.getLong(3) <= now() - 60000
      } finally rows.close()
    } finally counts.close()
    if (permitted) {
      val insert = connection.prepareStatement("INSERT INTO email_signin_requests(email_hash, requested_at) VALUES (?, ?)")
      try { insert.setString(1, key); insert.setLong(2, now()); insert.executeUpdate() } finally insert.close()
    }
    permitted
  }
}
