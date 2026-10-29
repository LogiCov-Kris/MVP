package logicov.config

import fabric.rw.*

case class Config(host: String = "0.0.0.0",
                  port: Int = 8300,
                  db: DBConfig,
                  production: Boolean = false,
                  webDirectory: String = "app/out") derives RW

object Config {
  def environment(base: Config, env: Map[String, String]): Config = {
    val production = env.get("LOGICOV_ENV").contains("production")
    require(!env.contains("K_SERVICE") || production, "Cloud Run requires LOGICOV_ENV=production")
    if (production) {
      List("LOGICOV_DB_URL", "LOGICOV_DB_USER", "LOGICOV_DB_PASSWORD").foreach { key =>
        require(env.get(key).exists(_.nonEmpty), s"$key is required in production")
      }
    }
    val result = base.copy(
      port = env.get("PORT").map(_.toInt).getOrElse(base.port),
      production = production,
      webDirectory = env.getOrElse("LOGICOV_WEB_DIRECTORY", base.webDirectory),
      db = base.db.copy(url = env.getOrElse("LOGICOV_DB_URL", base.db.url),
        user = env.getOrElse("LOGICOV_DB_USER", base.db.user),
        password = env.getOrElse("LOGICOV_DB_PASSWORD", base.db.password),
        maximumPoolSize = env.get("LOGICOV_DB_POOL_SIZE").map(_.toInt).getOrElse(8)))
    require(result.port > 0 && result.port <= 65535, "PORT must be between 1 and 65535")
    require(result.db.maximumPoolSize >= 1 && result.db.maximumPoolSize <= 32, "Database pool must contain 1 to 32 connections")
    result
  }
}
