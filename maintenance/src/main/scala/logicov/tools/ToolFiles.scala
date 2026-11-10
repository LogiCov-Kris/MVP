package logicov.tools

import fabric.{Json, Null}
import fabric.io.{JsonFormatter, JsonParser}
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import scala.jdk.CollectionConverters.*

object ToolFiles {
  def sha(bytes: Array[Byte]): String = MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 255}%02x").mkString
  def read(path: Path): Json = JsonParser(Files.readString(path))
  def write(path: Path, value: Json): Unit = {
    Option(path.getParent).foreach(Files.createDirectories(_))
    Files.writeString(path, JsonFormatter.Default(value))
    ()
  }
  def files(path: Path): List[Path] = {
    val stream = Files.walk(path)
    try stream.iterator().asScala.filter(Files.isRegularFile(_)).toList.sorted finally stream.close()
  }
  def remove(path: Path): Unit = {
    if (Files.exists(path)) {
      val stream = Files.walk(path)
      try stream.iterator().asScala.toList.sortBy(_.getNameCount).reverse.foreach(Files.delete) finally stream.close()
    }
  }
  def array(value: Json): List[Json] = value.asArr.value.toList
  def optional(value: Json, key: String): Option[Json] = value.asObj.value.get(key).filter(_ != Null)
  def string(value: Json, key: String): String = optional(value, key).fold("")(_.asString)
}
