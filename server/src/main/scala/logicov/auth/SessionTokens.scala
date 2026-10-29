package logicov.auth

import java.nio.charset.StandardCharsets
import java.security.{MessageDigest, SecureRandom}
import java.util.{Base64, HexFormat}

object SessionTokens {
  private val random = new SecureRandom()

  def create(): String = {
    val bytes = new Array[Byte](32)
    random.nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
  }

  def digest(token: String): String = HexFormat.of().formatHex(
    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)))
}
