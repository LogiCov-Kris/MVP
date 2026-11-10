package logicov.tools

import fabric.Json
import fabric.io.JsonFormatter
import fabric.rw.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.io.ByteArrayOutputStream
import java.util.zip.{ZipEntry, ZipOutputStream}

class ArtifactCheckSpec extends AnyFlatSpec with Matchers {
  private val deal = Map("approval" -> Map("revision" -> 1.json).json, "documents" -> List(Map("name" -> "statement.csv".json).json).json).json
  private def bundle(link: String = "audit.html#source", changed: Boolean = false): Array[Byte] = {
    val files = Map(
      "memo.pdf" -> "%PDF-fixture",
      "memo.html" -> s"<a href='$link'>Source</a>",
      "audit.html" -> "<p id='source'>Source</p>",
      "approved-workspace.json" -> JsonFormatter.Default(deal),
      "source-statement.csv" -> "revenue,100")
    val hashes = files.map((name, content) => name -> ToolFiles.sha(content.getBytes("UTF-8")))
    val bytes = new ByteArrayOutputStream()
    val zip = new ZipOutputStream(bytes)
    try {
      (files.updated("source-statement.csv", if (changed) "revenue,200" else files("source-statement.csv")) +
        ("SHA256SUMS.json" -> JsonFormatter.Default(hashes.json))).foreach { (name, content) =>
        zip.putNextEntry(new ZipEntry(name)); zip.write(content.getBytes("UTF-8")); zip.closeEntry()
      }
    } finally zip.close()
    bytes.toByteArray
  }
  "Artifact verification" should "accept retained sources with valid hashes and citation fragments" in {
    ArtifactCheck.verify(bundle(), deal)
  }
  it should "reject changed sources and broken citation fragments" in {
    intercept[IllegalArgumentException](ArtifactCheck.verify(bundle(changed = true), deal))
    intercept[IllegalArgumentException](ArtifactCheck.verify(bundle(link = "audit.html#missing"), deal))
    intercept[IllegalArgumentException](ArtifactCheck.verify(bundle(link = "absent.html"), deal))
  }
}
