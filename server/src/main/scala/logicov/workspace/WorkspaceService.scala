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
    if (WorkspaceNarratives.configured(sys.env) != NarrativeMode.Disabled) {
      LogiCovSigil.instance.sync()
    }
    workflow
    ()
  }
}
