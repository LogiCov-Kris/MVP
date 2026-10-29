package logicov.poc

import fabric.io.JsonFormatter
import fabric.rw.*

import java.nio.file.{Files, Path, StandardCopyOption}
import java.security.MessageDigest
import java.util.zip.{ZipEntry, ZipOutputStream}
import scala.jdk.CollectionConverters.*

/**
 * Packages a review round: `BundleApp <output dir> [readme.txt]` zips each memo with its HTML, audit companion, deal
 * record and retained sources, plus a SHA256SUMS.json, so the evidence links keep working wherever the bundle is opened.
 */
object BundleApp {
  private val Bundle = "logicov-review-bundle.zip"
  private val Checksums = "SHA256SUMS.json"
  private val Readme = "README.txt"
  private val Included = """[a-z]+-(memo\.pdf|memo\.html|audit\.html|deal\.json)|[a-z]+-sources/.+""".r

  def main(args: Array[String]): Unit = {
    if (args.isEmpty) throw new IllegalArgumentException("Usage: BundleApp <output dir> [readme.txt]")
    val output = Path.of(args.head)
    args.lift(1).foreach(readme => Files.copy(Path.of(readme), output.resolve(Readme), StandardCopyOption.REPLACE_EXISTING))
    val files = {
      val walk = Files.walk(output)
      try walk.iterator().asScala.filter(Files.isRegularFile(_)).map(output.relativize(_).toString)
        .filter(name => Included.matches(name) || name == Readme).toList.sorted
      finally walk.close()
    }
    val sums = files.map(name => name -> sha256(Files.readAllBytes(output.resolve(name)))).toMap
    Files.writeString(output.resolve(Checksums), JsonFormatter.Default(sums.json))
    val zip = new ZipOutputStream(Files.newOutputStream(output.resolve(Bundle)))
    try (files :+ Checksums).foreach { name =>
      zip.putNextEntry(new ZipEntry(name))
      zip.write(Files.readAllBytes(output.resolve(name)))
      zip.closeEntry()
    } finally zip.close()
    scribe.info(s"${files.size} files in ${output.resolve(Bundle)}")
  }

  private def sha256(bytes: Array[Byte]): String = MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString
}
