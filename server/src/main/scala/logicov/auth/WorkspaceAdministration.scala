package logicov.auth

import lightdb.id.Id
import logicov.db.DB
import logicov.model.{ManagedWorkspace, SessionView, UserAction, UserAudit, UserRole, WorkspaceAction, WorkspaceAudit, WorkspaceRequest}
import rapid.Task

object WorkspaceAdministration {
  private def authorized(actor: SessionView, users: List[User]): Unit = {
    require(users.exists(u => u.email == actor.email && u.tenantId.value == actor.tenantId && u.active &&
      u.role == UserRole.Admin && u.platformAdmin), "Platform administrator access required.")
  }

  def list(actor: SessionView): Task[List[ManagedWorkspace]] = DB.users.transaction(_.stream.toList).flatMap { users =>
    authorized(actor, users)
    DB.tenants.transaction(_.stream.toList).map { tenants =>
      tenants.sortBy(_.name.toLowerCase).map { tenant =>
        val members = users.filter(_.tenantId == tenant._id)
        ManagedWorkspace(tenant._id.value, tenant.name, tenant.active, tenant.adminRevision, members.size,
          members.count(_.active), members.count(u => u.active && u.role == UserRole.Admin),
          members.flatMap(_.lastSignIn).maxOption, tenant.adminHistory)
      }
    }
  }

  def change(actor: SessionView, request: WorkspaceRequest): Task[Unit] = Task.defer {
    val name = request.name.trim
    require(name.nonEmpty && name.length <= 120, "Workspace name required (up to 120 characters).")
    val email = AuthService.email(request.administratorEmail)
    if (request.action == WorkspaceAction.Create) {
      require(email.length <= 254 && email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"), "A valid first administrator email is required.")
      require(request.id.isEmpty, "New workspaces cannot specify an existing workspace ID.")
      require(request.active, "New workspaces must start active.")
    }
    val hash = if (request.action == WorkspaceAction.Create) Passwords.hash(SessionTokens.create()) else ""
    DB.users.transaction { users =>
      val lock = users.state.openConnection().createStatement()
      try lock.execute("SELECT pg_advisory_xact_lock(1845249093)") finally lock.close()
      users.stream.toList.flatMap { snapshot =>
        val ids = snapshot.filter(u => u.email == actor.email || u.tenantId.value == request.id).map(_._id.value).sorted
        ids.foreach { id =>
          val row = users.state.openConnection().prepareStatement(s"SELECT _id FROM ${users.store.fqn} WHERE _id = ? FOR UPDATE")
          try { row.setString(1, id); row.executeQuery().close() } finally row.close()
        }
        users.stream.toList.flatMap { all =>
          authorized(actor, all)
          DB.tenants.transaction.joining(users) { tenants =>
            tenants.stream.toList.flatMap { existing =>
              require(!existing.exists(t => t.name.equalsIgnoreCase(name) && t._id.value != request.id), "A workspace with this name already exists.")
              val now = System.currentTimeMillis()
              request.action match {
                case WorkspaceAction.Create =>
                  require(!all.exists(_.email == email), "This email already has an account. Use an email not assigned to another workspace.")
                  val tenant = Tenant(name, adminRevision = 1, adminHistory = List(WorkspaceAudit(WorkspaceAction.Create, actor.email, now, "Workspace created with its first administrator")))
                  val admin = User(tenant._id, email, hash, role = UserRole.Admin, _id = Id[User](SessionTokens.digest(email)),
                    adminRevision = 1, adminHistory = List(UserAudit(UserAction.Create, actor.email, now, "Workspace administrator created")))
                  tenants.insert(tenant).flatMap(_ => users.insert(admin)).unit
                case WorkspaceAction.Update =>
                  val tenant = existing.find(_._id.value == request.id).getOrElse(throw new NoSuchElementException("Workspace not found."))
                  require(tenant.adminRevision == request.revision, "This workspace changed. Refresh workspaces and try again.")
                  require(request.active || !all.exists(u => u.tenantId == tenant._id && u.platformAdmin), "A workspace containing platform administrators cannot be suspended.")
                  require(!request.active || all.exists(u => u.tenantId == tenant._id && u.active && u.role == UserRole.Admin), "An active workspace needs an active administrator.")
                  val members = all.filter(_.tenantId == tenant._id)
                  val revoke = if (tenant.active && !request.active) members.foldLeft(Task.unit) { (previous, user) =>
                    previous.flatMap(_ => users.upsert(user.copy(accessVersion = user.accessVersion + 1, adminRevision = user.adminRevision + 1,
                      adminHistory = user.adminHistory :+ UserAudit(UserAction.RevokeSessions, actor.email, now, "Workspace suspended; sessions and authentication links revoked"))).unit)
                  } else Task.unit
                  revoke.flatMap(_ => tenants.upsert(tenant.copy(name = name, active = request.active, adminRevision = tenant.adminRevision + 1,
                    adminHistory = tenant.adminHistory :+ WorkspaceAudit(WorkspaceAction.Update, actor.email, now,
                      s"$name · ${if (request.active) "Active" else "Suspended"}"))).unit)
              }
            }
          }
        }
      }
    }
  }
}
