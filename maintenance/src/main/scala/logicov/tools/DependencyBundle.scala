package logicov.tools

import fabric.rw.*
import fabric.io.{JsonFormatter, JsonParser}
import org.apache.commons.compress.archivers.tar.{TarArchiveEntry, TarArchiveInputStream, TarArchiveOutputStream}
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.file.{Files, LinkOption, Path}
import java.util.zip.{GZIPInputStream, GZIPOutputStream}
import scala.collection.mutable

object DependencyBundle {
  def install(archive: Path, expected: String, destination: Path): Int = {
    require(ToolFiles.sha(Files.readAllBytes(archive)) == expected, "Dependency bundle checksum does not match the reviewed version")
    val tar = new TarArchiveInputStream(new GZIPInputStream(Files.newInputStream(archive)))
    val entries = mutable.LinkedHashMap.empty[String, Array[Byte]]
    try {
      var entry = tar.getNextEntry
      while (entry != null) {
        val name = entry.getName
        require(entry.isFile && !entry.isSymbolicLink && !entry.isLink && !entries.contains(name), "Non-regular or duplicate archive entry")
        require(name == "SHA256SUMS.json" || (name.startsWith("local/") && !name.contains('\\') &&
          name.split('/').forall(p => p.nonEmpty && p != "." && p != "..")), "Unsafe dependency archive path")
        entries(name) = tar.readAllBytes()
        entry = tar.getNextEntry
      }
    } finally tar.close()
    val manifest = JsonParser(new String(entries.getOrElse("SHA256SUMS.json", throw new IllegalArgumentException("Missing manifest")), "UTF-8")).as[Map[String, String]]
    val payloads = entries.toMap - "SHA256SUMS.json"
    require(payloads.keySet.map(_.stripPrefix("local/")) == manifest.keySet, "Dependency manifest coverage mismatch")
    val root = destination.toAbsolutePath.normalize()
    val validated = payloads.toList.map { (name, bytes) =>
      require(ToolFiles.sha(bytes) == manifest(name.stripPrefix("local/")), "Dependency payload checksum does not match")
      val target = root.resolve(name)
      var parent = target
      while (parent != null) {
        require(!Files.isSymbolicLink(parent), "Dependency destination contains a symbolic link")
        parent = parent.getParent
      }
      target -> bytes
    }
    validated.foreach { (path, bytes) => Files.createDirectories(path.getParent); Files.write(path, bytes) }
    validated.size
  }

  def prepare(root: Path, local: Path, output: Path): Unit = {
    val build = Files.readString(root.resolve("build.sbt"))
    val versions = "val (\\w+Version): String = \"([^\"]+)\"".r.findAllMatchIn(build).map(m => m.group(1) -> m.group(2)).toMap
    val seeds = "\"([^\"]+)\"\\s+%%?\\s+\"([^\"]+)\"\\s+%\\s+(\\w+Version)".r.findAllMatchIn(build).flatMap { m =>
      List(m.group(2), m.group(2) + "_3", m.group(2) + "_sjs1_3").map(a => s"${m.group(1)}/$a/${versions(m.group(3))}")
    }.toList
    val plugins = "\"([^\"]+)\"\\s+%\\s+\"([^\"]+)\"\\s+%\\s+\"([^\"]+)\"".r.findAllMatchIn(Files.readString(root.resolve("project/plugins.sbt"))).flatMap { m =>
      List(m.group(2), m.group(2) + "_sbt2_3").map(a => s"${m.group(1)}/$a/${m.group(3)}")
    }.toList
    val pending = mutable.Queue.from((seeds ++ plugins).filter(c => Files.exists(local.resolve(s"$c/ivys/ivy.xml"))))
    val seen = mutable.Set.empty[String]
    val files = mutable.Map.empty[String, Array[Byte]]
    val excluded = Set("test", "docs", "sources", "scala-tool", "scala-doc-tool", "scala-repl-tool", "zinc-tool")
    while (pending.nonEmpty) {
      val coordinate = pending.dequeue()
      if (seen.add(coordinate)) {
        val dir = local.resolve(coordinate)
        val document = ToolXml.read(dir.resolve("ivys/ivy.xml"))
        ToolXml.descendants(document.getDocumentElement, "dependency").foreach { dep =>
          val configs = dep.getAttribute("conf").split(';').map(_.split("->").head).toSet
          if (configs.nonEmpty && configs.subsetOf(excluded)) dep.getParentNode.removeChild(dep)
          else {
            val child = List("org", "name", "rev").map(dep.getAttribute).mkString("/")
            if (Files.exists(local.resolve(s"$child/ivys/ivy.xml"))) pending.enqueue(child)
          }
        }
        val binaries = ToolFiles.files(dir.resolve("jars")).filter(p => p.toString.endsWith(".jar") && !List("-tests.jar", "-sources.jar", "-javadoc.jar").exists(p.toString.endsWith))
        require(binaries.nonEmpty, s"No binaries found for $coordinate")
        ToolXml.descendants(document.getDocumentElement, "publications").flatMap(ToolXml.children).foreach { artifact =>
          val attrs = artifact.getAttributes
          if (artifact.getAttribute("type") != "jar" || (0 until attrs.getLength).exists(i => attrs.item(i).getNodeName.endsWith("classifier"))) artifact.getParentNode.removeChild(artifact)
        }
        files(s"$coordinate/ivys/ivy.xml") = ToolXml.bytes(document)
        binaries.foreach(p => files(s"$coordinate/jars/${p.getFileName}") = Files.readAllBytes(p))
      }
    }
    require(files.nonEmpty, "No local dependency binaries found")
    Files.createDirectories(output)
    val archive = output.resolve("logicov-build-dependencies.tar.gz")
    val tar = new TarArchiveOutputStream(new GZIPOutputStream(Files.newOutputStream(archive)))
    tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
    def append(name: String, bytes: Array[Byte]): Unit = {
      val entry = new TarArchiveEntry(name)
      entry.setSize(bytes.length); entry.setMode(420); entry.setModTime(0L)
      tar.putArchiveEntry(entry); tar.write(bytes); tar.closeArchiveEntry()
    }
    try {
      files.toList.sortBy(_._1).foreach((name, bytes) => append("local/" + name, bytes))
      append("SHA256SUMS.json", JsonFormatter.Default(files.toMap.view.mapValues(ToolFiles.sha).toMap.json).getBytes("UTF-8"))
    } finally tar.close()
    val digest = ToolFiles.sha(Files.readAllBytes(archive))
    Files.writeString(output.resolve("SHA256SUM"), digest + "\n")
    println(s"${seen.size} binary modules, ${files.size} files: $archive\nSHA256: $digest")
  }
}
