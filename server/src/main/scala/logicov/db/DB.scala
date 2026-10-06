package logicov.db

import lightdb.LightDB
import lightdb.postgresql.PostgreSQLStoreManager
import lightdb.sql.connect.{HikariConnectionManager, SQLConfig}
import lightdb.store.CollectionManager
import lightdb.upgrade.DatabaseUpgrade
import logicov.App

import java.nio.file.Path

object DB extends LightDB {
  override type SM = CollectionManager
  override val storeManager: SM = {
    val db = App.config.db
    PostgreSQLStoreManager(HikariConnectionManager(SQLConfig(
      jdbcUrl = db.url,
      username = Some(db.user),
      password = Some(db.password),
      maximumPoolSize = Some(db.maximumPoolSize)
    )))
  }

  override lazy val directory: Option[Path] = None

  override def upgrades: List[DatabaseUpgrade] = Nil
}
