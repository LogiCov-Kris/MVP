package logicov.config

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ConfigSpec extends AnyFlatSpec with Matchers {
  private val base = Config(db = DBConfig("jdbc:postgresql://localhost/local", "local", "local"))

  "Runtime configuration" should "reject production without explicit database secrets" in {
    intercept[IllegalArgumentException](Config.environment(base, Map("LOGICOV_ENV" -> "production")))
    intercept[IllegalArgumentException](Config.environment(base, Map("K_SERVICE" -> "logicov")))
  }

  it should "honor the platform port and explicit production configuration" in {
    val c = Config.environment(base, Map("LOGICOV_ENV" -> "production", "PORT" -> "8080",
      "LOGICOV_DB_URL" -> "jdbc:postgresql:///logicov", "LOGICOV_DB_USER" -> "runtime", "LOGICOV_DB_PASSWORD" -> "secret"))
    c.port should be(8080)
    c.production should be(true)
    c.db.user should be("runtime")
    c.db.maximumPoolSize should be(8)
    intercept[IllegalArgumentException](Config.environment(base, Map("PORT" -> "0")))
  }
}
