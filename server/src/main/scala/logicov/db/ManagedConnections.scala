package logicov.db

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import lightdb.sql.connect.DataSourceConnectionManager
import logicov.config.DBConfig
import rapid.Task

import java.sql.Connection

class ManagedConnections(config: DBConfig) extends DataSourceConnectionManager {
  override protected lazy val dataSource: HikariDataSource = {
    val pool = new HikariConfig()
    pool.setJdbcUrl(config.url)
    pool.setUsername(config.user)
    pool.setPassword(config.password)
    pool.setAutoCommit(false)
    pool.setMaximumPoolSize(config.maximumPoolSize)
    pool.setMinimumIdle(0)
    pool.setConnectionTimeout(10000)
    pool.setValidationTimeout(3000)
    new HikariDataSource(pool)
  }

  def jdbc[A](work: Connection => A): A = {
    val connection = dataSource.getConnection
    try {
      val result = work(connection)
      connection.commit()
      result
    } catch {
      case cause: Throwable =>
        try connection.rollback() catch { case rollback: Throwable => cause.addSuppressed(rollback) }
        throw cause
    } finally connection.close()
  }

  def healthy(): Boolean = {
    val connection = dataSource.getConnection
    try connection.isValid(3)
    finally connection.close()
  }

  override protected def doDispose(): Task[Unit] = Task(dataSource.close())
}
