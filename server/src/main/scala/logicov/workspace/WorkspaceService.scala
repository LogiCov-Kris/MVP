package logicov.workspace

import logicov.App
import logicov.ai.LogiCovSigil
import logicov.db.DB

object WorkspaceService {
  lazy val database = new WorkspaceDatabase(DB.connections)
  lazy val store = new WorkspaceStore(database, TenantCipher.configured(App.config.production))
  lazy val workflow = new WorkspaceWorkflow(store)

  def initialize(): Unit = {
    if (!App.config.production) database.initialize()
    database.verify()
    if (sys.env.get("LOGICOV_NARRATIVES_ENABLED").contains("true")) {
      require(sys.env.get("LOGICOV_PROVIDER_APPROVED").contains("true"), "Approve the client-owned provider account for this environment before enabling narratives")
      LogiCovSigil.instance.sync()
    }
    workflow
    ()
  }
}
