package logicov.auth

import lightdb.id.Id
import logicov.config.LogiCovConfig
import logicov.db.DB
import logicov.model.{UserRole, UserAudit, UserAction}

object AccountAdminApp {
  def main(args: Array[String]): Unit = {
    require(args.length == 2, "Usage: AccountAdminApp <email> <Admin|Analyst|platform|workspace|remove>")
    LogiCovConfig.load()
    DB.init.sync()
    try {
      val id = Id[User](SessionTokens.digest(AuthService.email(args(0))))
      val user = DB.users.transaction(_.get(id)).sync().getOrElse(throw new IllegalArgumentException("Account not found"))
      if (args(1) == "remove") {
        val sessions = DB.sessions.transaction(_.stream.toList).sync().filter(_.userId == id)
        sessions.foreach(session => DB.sessions.transaction(_.delete(session._id)).sync())
        DB.users.transaction(_.delete(id)).sync()
        println(s"Removed ${user.email}")
      } else {
        val role = if (args(1) == "platform" || args(1) == "workspace") UserRole.Admin else UserRole.valueOf(args(1))
        val platform = args(1) == "platform" || (args(1) == "Admin" && user.platformAdmin)
        DB.users.transaction(_.upsert(user.copy(role = role, platformAdmin = platform, accessVersion = user.accessVersion + 1,
          adminRevision = user.adminRevision + 1, adminHistory = user.adminHistory :+
            UserAudit(UserAction.Update, "Operator", System.currentTimeMillis(), s"Role: $role; platform administration: $platform")))).sync()
        println(s"Updated ${user.email}: $role")
      }
    } finally DB.dispose.sync()
  }
}
