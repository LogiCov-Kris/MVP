package logicov.analysis

import logicov.deal.{DealBuilder, DealRecord}
import logicov.edgar.SourcePack

import java.nio.file.{FileSystems, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * The three sample deals, built from trimmed copies of their real source packs.
 */
object SampleDeals {
  lazy val kinderMorgan: DealRecord = DealBuilder.build(pack("kmi"))
  lazy val westlake: DealRecord = DealBuilder.build(pack("wlk"))
  lazy val occidental: DealRecord = DealBuilder.build(pack("oxy"))

  def pack(name: String): SourcePack = {
    val uri = getClass.getClassLoader.getResource(s"packs/$name").toURI
    if (uri.getScheme == "jar") {
      Try(FileSystems.newFileSystem(uri, Map.empty[String, Any].asJava))
    }
    SourcePack(Path.of(uri))
  }
}
