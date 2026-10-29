package logicov.auth

import lightdb.id.Id
import logicov.config.LogiCovConfig
import logicov.db.DB
import logicov.model.UserRole

object AccountAdminApp {
  def main(args: Array[String]): Unit = {
    require(args.length == 2, "Usage: AccountAdminApp <email> <Admin|Analyst|remove>")
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
        val role = UserRole.valueOf(args(1))
        DB.users.transaction(_.upsert(user.copy(role = role))).sync()
        println(s"Updated ${user.email}: $role")
      }
    } finally DB.dispose.sync()
  }
}
