package logicov.tools

import fabric.io.JsonFormatter
import fabric.rw.*
import org.apache.commons.compress.archivers.tar.{TarArchiveEntry, TarArchiveOutputStream}
import java.nio.file.{Files, Path}
import java.util.zip.GZIPOutputStream

trait ToolSpecSupport {
  def temporary[A](run: Path => A): A = {
    val root = Files.createTempDirectory("logicov-tools-")
    try run(root) finally ToolFiles.remove(root)
  }
  def archive(root: Path, entries: List[(String, Array[Byte])], manifest: Map[String, String]): Path = {
    val path = root.resolve("dependencies.tar.gz")
    val tar = new TarArchiveOutputStream(new GZIPOutputStream(Files.newOutputStream(path)))
    try {
      (entries :+ ("SHA256SUMS.json" -> JsonFormatter.Compact(manifest.json).getBytes("UTF-8"))).foreach { (name, bytes) =>
        val entry = new TarArchiveEntry(name, true)
        entry.setSize(bytes.length)
        tar.putArchiveEntry(entry); tar.write(bytes); tar.closeArchiveEntry()
      }
    } finally tar.close()
    path
  }
}
