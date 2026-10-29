package logicov.db

import lightdb.LightDB
import lightdb.postgresql.PostgreSQLStoreManager
import lightdb.upgrade.DatabaseUpgrade
import logicov.App
import logicov.auth.{Session, Tenant, User}

import java.nio.file.Path

object DB extends LightDB {
  override type SM = PostgreSQLStoreManager
  val connections = new ManagedConnections(App.config.db)
  override val storeManager: SM = PostgreSQLStoreManager(connections)
  override lazy val directory: Option[Path] = None
  val tenants = store(Tenant)()
  val users = store(User)()
  val sessions = store(Session)()
  override def upgrades: List[DatabaseUpgrade] = Nil
}
