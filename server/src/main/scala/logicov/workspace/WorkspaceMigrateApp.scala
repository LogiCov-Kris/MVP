package logicov.workspace

import logicov.config.LogiCovConfig
import logicov.auth.EmailSignIn
import logicov.db.DB

object WorkspaceMigrateApp {
  def main(args: Array[String]): Unit = {
    require(args.isEmpty, "WorkspaceMigrateApp takes no arguments")
    LogiCovConfig.load()
    DB.init.sync()
    try {
      val database = new WorkspaceDatabase(DB.connections)
      database.initialize()
      database.verify()
      EmailSignIn.migrate()
      println("Workspace schema and row-level security verified")
    } finally DB.dispose.sync()
  }
}
