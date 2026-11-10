package logicov.tools

import fabric.Json
import fabric.io.JsonParser
import fabric.rw.*
import java.io.ByteArrayInputStream
import java.net.{URI, URLDecoder}
import java.nio.file.{Files, Path}
import java.util.zip.ZipInputStream
import scala.collection.mutable

object ArtifactCheck {
  def verify(bytes: Array[Byte], deal: Json): Unit = {
    val zip = new ZipInputStream(new ByteArrayInputStream(bytes))
    val files = mutable.Map.empty[String, Array[Byte]]
    try {
      var entry = zip.getNextEntry
      while (entry != null) {
        require(!entry.isDirectory && !files.contains(entry.getName), "Duplicate or non-file bundle entry")
        files(entry.getName) = zip.readAllBytes()
        entry = zip.getNextEntry
      }
    } finally zip.close()
    val hashes = JsonParser(new String(files("SHA256SUMS.json"), "UTF-8")).as[Map[String, String]]
    require(hashes.keySet == files.keySet.toSet - "SHA256SUMS.json", "Bundle manifest coverage mismatch")
    hashes.foreach((name, hash) => require(ToolFiles.sha(files(name)) == hash, s"Bundle hash mismatch: $name"))
    require(new String(files("memo.pdf").take(5), "UTF-8") == "%PDF-", "Memo is not a PDF")
    val pages = List("memo.html", "audit.html").map(name => name -> new String(files(name), "UTF-8")).toMap
    val ids = pages.view.mapValues(page => "(?i)\\bid=[\"']([^\"']+)[\"']".r.findAllMatchIn(page).map(_.group(1)).toSet).toMap
    pages.foreach { (name, page) =>
      "(?i)<a\\b[^>]*\\bhref=[\"']([^\"']+)[\"']".r.findAllMatchIn(page).foreach { m =>
        val uri = URI.create(m.group(1).replace("&amp;", "&"))
        if (!uri.isAbsolute) {
          val target = Option(uri.getPath).filter(_.nonEmpty).getOrElse(name)
          require(files.contains(target), s"Broken bundle link: $name -> $target")
          Option(uri.getFragment).filter(_.nonEmpty).foreach { fragment =>
            ids.get(target).foreach(anchors => require(anchors.contains(fragment), s"Broken citation: $target#$fragment"))
          }
        }
      }
    }
    val approved = JsonParser(new String(files("approved-workspace.json"), "UTF-8"))
    require(approved("approval") == deal("approval"), "Approved snapshot changed")
    val sourceCount = ToolFiles.array(deal("documents")).count(d => !d("name").asString.startsWith("generated-"))
    require(files.keys.count(_.startsWith("source-")) == sourceCount, "Missing retained sources")
  }
  def run(output: Path): Unit = {
    val base = sys.env("LOGICOV_TEST_URL")
    val client = new ToolHttp(base)
    val anonymous = new ToolHttp(base)
    try {
      client.login(sys.env("LOGICOV_TEST_EMAIL"), sys.env("LOGICOV_TEST_PASSWORD"))
      anonymous.request("/api/deals").expect(401)
      val referenceNames = Set("KMI", "WLK", "OXY")
      val deals = ToolFiles.array(client.data("/api/deals")).map(s => client.data("/api/deals/" + s("id").asString))
        .filter(d => ToolFiles.optional(d, "record").exists(r => referenceNames.contains(r("ticker").asString.toUpperCase)))
      require(deals.size == 3, "Expected exactly three saved reference borrowers")
      Files.createDirectories(output)
      deals.foreach { deal =>
        val route = "/api/deals/" + deal("id").asString
        anonymous.request(route).expect(401)
        val documents = ToolFiles.array(deal("documents"))
        anonymous.request(route + "/files/" + documents.head("id").asString).expect(401)
        require(Set("Completed", "Submitted").contains(deal("stage").asString.split('.').last), "Generate and review the reference memo before verifying artifacts")
        val bundle = documents.reverse.find(_("contentType").asString == "application/zip").getOrElse(throw new IllegalArgumentException("Missing review bundle"))
        val bytes = client.request(route + "/files/" + bundle("id").asString).expect(200).bytes
        require(ToolFiles.sha(bytes) == bundle("sha256").asString, "Saved bundle hash mismatch")
        verify(bytes, deal)
        Files.write(output.resolve(deal("record")("ticker").asString.toLowerCase + "-review.zip"), bytes)
      }
      println("Three reference artifacts verified: approved snapshots, source citations, PDF and bundle hashes")
    } finally { client.close(); anonymous.close() }
  }
}
