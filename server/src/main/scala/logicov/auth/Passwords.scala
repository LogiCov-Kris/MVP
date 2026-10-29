package logicov.auth

import com.outr.scalapass.Argon2PasswordFactory

object Passwords {
  private val factory = Argon2PasswordFactory(iterations = 3, memory = 65536, parallelism = 1)
  lazy val dummy: String = factory.hash("unavailable-account-check")

  def hash(password: String): String = {
    require(password.length >= 14 && password.length <= 256, "Passwords must contain 14 to 256 characters")
    factory.hash(password)
  }

  def verify(password: String, hash: String): Boolean = factory.verify(password, hash)
}
