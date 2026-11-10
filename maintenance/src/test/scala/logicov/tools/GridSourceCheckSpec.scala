package logicov.tools

import fabric.io.JsonParser
import fabric.rw.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.Files

class GridSourceCheckSpec extends AnyFlatSpec with Matchers with ToolSpecSupport {
  "Independent source verification" should "check raw components and reject a changed grid amount" in temporary { root =>
    val records = Files.createDirectory(root.resolve("records"))
    val packs = Files.createDirectory(root.resolve("packs"))
    val cell = JsonParser("""{"item":"Cash","value":15,"period":{"label":"FY2025","start":null,"end":"2025-12-31"},"source":{"type":"Source.Xbrl","tag":"Cash less RestrictedCash plus Investments","accession":"source","form":"10-K"}}""")
    List("kmi", "wlk", "oxy").foreach { ticker =>
      ToolFiles.write(records.resolve(s"$ticker-deal.json"), Map("grid" -> Map("values" -> List(cell).json).json, "peers" -> List.empty[String].json).json)
      val sec = Files.createDirectories(packs.resolve(s"$ticker/sec"))
      val concepts = List("Cash" -> 20000000, "RestrictedCash" -> 7000000, "Investments" -> 2000000).map { (tag, amount) =>
        val fact = Map("val" -> amount.json, "end" -> "2025-12-31".json, "form" -> "10-K".json, "accn" -> "source".json, "filed" -> "2026-01-31".json).json
        tag -> Map("units" -> Map("USD" -> List(fact).json).json).json
      }.toMap
      ToolFiles.write(sec.resolve("companyfacts.json"), Map("facts" -> Map("us-gaap" -> concepts.json).json).json)
    }
    val report = root.resolve("report.json")
    GridSourceCheck.run(records, packs, "", report)
    ToolFiles.read(report)("verified").asInt should be(3)
    val record = ToolFiles.read(records.resolve("kmi-deal.json"))
    val altered = cell.asObj.value.updated("value", 16.json).json
    ToolFiles.write(records.resolve("kmi-deal.json"), record.asObj.value.updated("grid", Map("values" -> List(altered).json).json).json)
    intercept[IllegalArgumentException](GridSourceCheck.run(records, packs, "", report))
    ToolFiles.read(report)("unresolved").asInt should be(1)
  }
}
