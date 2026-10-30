package logicov.auth

import lightdb.id.Id
import logicov.db.DB
import logicov.model.{SessionView, UserAction, UserAudit, UserRequest, UserRole, WorkspaceUser}
import rapid.Task

object UserAdministration {
  private def authorized(actor: SessionView, users: List[User], workspaceId: String): Unit = {
    require(users.exists(u => u.email == actor.email && u.tenantId.value == actor.tenantId && u.active && u.role == UserRole.Admin && (workspaceId == actor.tenantId || u.platformAdmin)), "Administrator access required.")
  }

  def list(actor: SessionView, workspaceId: String = ""): Task[List[WorkspaceUser]] = DB.users.transaction(_.stream.toList).flatMap { users =>
    val targetId = if (workspaceId.isEmpty) actor.tenantId else workspaceId
    authorized(actor, users, targetId)
    DB.sessions.transaction(_.stream.toList).map { sessions =>
      users.filter(_.tenantId.value == targetId).sortBy(_.email).map { u =>
        val live = if (!u.active) 0 else sessions.count(s => s.userId == u._id && s.expiresAt > System.currentTimeMillis() &&
          s.accessVersion == u.accessVersion && s.credentialHash == SessionTokens.digest(u.passwordHash))
        WorkspaceUser(u.email, u.role, u.active, u.adminRevision, u.lastSignIn, live, u.invitationAt, u.adminHistory, u.platformAdmin)
      }
    }
  }

  def change(actor: SessionView, request: UserRequest, sendInvitation: (String, String) => Unit = EmailSignIn.invite,
             emailEnabled: Boolean = EmailSignIn.enabled, workspaceId: String = ""): Task[Unit] = Task.defer {
    require(Set(UserAction.Create, UserAction.Update, UserAction.RevokeSessions, UserAction.Invite).contains(request.action), "Unsupported account action.")
    val targetId = if (workspaceId.isEmpty) actor.tenantId else workspaceId
    val email = AuthService.email(request.email)
    require(email.length <= 254 && email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"), "Enter a valid email address.")
    val id = Id[User](SessionTokens.digest(email))
    val passwordHash = if (request.action == UserAction.Create) Passwords.hash(SessionTokens.create()) else ""
    DB.users.transaction { users =>
      val connection = users.state.openConnection()
      val lock = connection.createStatement()
      try lock.execute("SELECT pg_advisory_xact_lock(1845249093)") finally lock.close()
      val rows = connection.prepareStatement(s"SELECT _id FROM ${users.store.fqn} WHERE _id IN (?, ?) ORDER BY _id FOR UPDATE")
      try {
        rows.setString(1, id.value)
        rows.setString(2, SessionTokens.digest(actor.email))
        rows.executeQuery().close()
      } finally rows.close()
      users.stream.toList.flatMap { all =>
        authorized(actor, all, targetId)
        DB.tenants.transaction.joining(users)(_.get(Id[Tenant](targetId))).flatMap { tenantOption =>
          val tenant = tenantOption.getOrElse(throw new NoSuchElementException("Workspace not found."))
          require(tenant.active || (request.action != UserAction.Create && request.action != UserAction.Invite), "Reactivate the workspace before adding or inviting users.")
          val current = all.find(_._id == id)
          val now = System.currentTimeMillis()
          val updated = request.action match {
            case UserAction.Create =>
              require(current.isEmpty, "This email cannot be added. Check existing users or contact the platform administrator.")
              User(Id[Tenant](targetId), email, passwordHash, role = request.role, _id = id)
            case _ =>
              val user = current.filter(_.tenantId.value == targetId).getOrElse(throw new NoSuchElementException("User not found."))
              require(!user.platformAdmin || all.exists(u => u.email == actor.email && u.platformAdmin), "Only a platform administrator can manage this account.")
              require(!user.platformAdmin || request.action != UserAction.Update || request.role == UserRole.Admin, "Platform administrators must retain the administrator role.")
              require(user.adminRevision == request.revision, "This account changed. Refresh users and try again.")
              request.action match {
                case UserAction.Update =>
                  require(email != actor.email || (request.active && request.role == UserRole.Admin), "You cannot suspend or remove your own administrator access.")
                  require(!user.active || user.role != UserRole.Admin || (request.active && request.role == UserRole.Admin) ||
                    all.exists(u => u.tenantId == user.tenantId && u._id != user._id && u.active && u.role == UserRole.Admin), "Keep at least one active administrator.")
                  user.copy(role = request.role, active = request.active, accessVersion = user.accessVersion + 1)
                case UserAction.RevokeSessions => user.copy(accessVersion = user.accessVersion + 1)
                case UserAction.Invite =>
                  require(user.active, "Reactivate this account before inviting it.")
                  require(emailEnabled, "Email invitations are not configured.")
                  require(user.invitationAt.forall(_ < now - 60000), "Wait one minute before sending another invitation.")
                  user.copy(invitationAt = Some(now))
                case _ => throw new IllegalArgumentException("Unsupported account action.")
              }
          }
          val detail = request.action match {
            case UserAction.Create | UserAction.Update => s"${updated.role} · ${if (updated.active) "Active" else "Suspended"}"
            case UserAction.RevokeSessions => "Existing sessions and outstanding sign-in links revoked"
            case UserAction.Invite => "Invitation requested"
            case _ => ""
          }
          users.upsert(updated.copy(adminRevision = updated.adminRevision + 1,
            adminHistory = updated.adminHistory :+ UserAudit(request.action, actor.email, now, detail))).map(_ => tenant.name)
        }
      }
    }.flatMap { tenantName =>
      if (request.action != UserAction.Invite) Task.unit
      else Task(sendInvitation(email, tenantName)).flatMap(_ => recordInvitation(id, actor.email, sent = true)).handleError { cause =>
        recordInvitation(id, actor.email, sent = false).flatMap(_ => Task.error(new IllegalStateException("Invitation could not be sent. The account is saved; retry the invitation later.", cause)))
      }
    }
  }

  private def recordInvitation(id: Id[User], actor: String, sent: Boolean): Task[Unit] = DB.users.transaction { users =>
    val rows = users.state.openConnection().prepareStatement(s"SELECT _id FROM ${users.store.fqn} WHERE _id = ? FOR UPDATE")
    try { rows.setString(1, id.value); rows.executeQuery().close() } finally rows.close()
    users.get(id).flatMap {
      case Some(user) => users.upsert(user.copy(adminRevision = user.adminRevision + 1, adminHistory = user.adminHistory :+
        UserAudit(if (sent) UserAction.InvitationSent else UserAction.InvitationFailed, actor, System.currentTimeMillis(),
          if (sent) "Email provider accepted the invitation; inbox delivery is not confirmed" else "Email provider request failed; retry available"))).unit
      case None => Task.unit
    }
  }
}
