package logicov.workspace

import java.nio.charset.StandardCharsets
import java.nio.channels.FileChannel
import java.nio.file.{Files, Path, StandardCopyOption, StandardOpenOption}
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}

class TenantCipher(master: Array[Byte]) {
  require(master.length == 32, "Encryption master key must contain 32 bytes")
  private val random = new SecureRandom()

  def newKey(): Array[Byte] = {
    val key = new Array[Byte](32)
    random.nextBytes(key)
    key
  }

  def wrap(tenant: String, key: Array[Byte]): Array[Byte] = encrypt(master, s"key:$tenant", key)
  def unwrap(tenant: String, value: Array[Byte]): Array[Byte] = decrypt(master, s"key:$tenant", value)

  def encrypt(key: Array[Byte], identity: String, plain: Array[Byte]): Array[Byte] = {
    val nonce = new Array[Byte](12)
    random.nextBytes(nonce)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce))
    cipher.updateAAD(identity.getBytes(StandardCharsets.UTF_8))
    nonce ++ cipher.doFinal(plain)
  }

  def decrypt(key: Array[Byte], identity: String, sealedBytes: Array[Byte]): Array[Byte] = {
    require(sealedBytes.length >= 28, "Invalid encrypted payload")
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, sealedBytes.take(12)))
    cipher.updateAAD(identity.getBytes(StandardCharsets.UTF_8))
    cipher.doFinal(sealedBytes.drop(12))
  }
}

object TenantCipher {
  def configured(production: Boolean): TenantCipher = {
    val key = sys.env.get("LOGICOV_MASTER_KEY").map(Base64.getDecoder.decode).getOrElse {
      require(!production, "LOGICOV_MASTER_KEY is required in production")
      val path = Path.of(sys.env.getOrElse("LOGICOV_MASTER_KEY_FILE", ".logicov/master.key"))
      Files.createDirectories(path.toAbsolutePath.getParent)
      val lockPath = path.resolveSibling(path.getFileName.toString + ".lock")
      val channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
      val lock = channel.lock()
      try {
        if (!Files.exists(path)) {
          val bytes = new Array[Byte](32)
          new SecureRandom().nextBytes(bytes)
          val temporary = Files.createTempFile(path.toAbsolutePath.getParent, "master-", ".key",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
          try {
            Files.write(temporary, bytes, StandardOpenOption.WRITE)
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE)
          } finally Files.deleteIfExists(temporary)
        }
      } finally { lock.release(); channel.close() }
      Files.readAllBytes(path)
    }
    new TenantCipher(key)
  }
}
