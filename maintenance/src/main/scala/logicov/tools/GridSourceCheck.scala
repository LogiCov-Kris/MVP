package logicov.tools

import fabric.Json
import fabric.rw.*
import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.util.control.NonFatal

object GridSourceCheck {
  def run(records: Path, packs: Path, suffix: String, output: Path): Unit = {
    val hashes = mutable.Map.empty[String, String]
    val cache = mutable.Map.empty[Path, Json]
    def read(path: Path): Json = cache.getOrElseUpdate(path, {
      hashes(path.toString) = ToolFiles.sha(Files.readAllBytes(path))
      ToolFiles.read(path)
    })
    val results = List("kmi", "wlk", "oxy").flatMap { ticker =>
      val record = read(records.resolve(s"$ticker-deal.json"))
      val grids = (ticker -> record("grid")) :: ToolFiles.array(record("peers")).map(p => p("ticker").asString -> p("grid"))
      grids.flatMap { (name, grid) =>
        val sec = packs.resolve(ticker + suffix).resolve("sec")
        val path = sec.resolve(if (name == ticker) "companyfacts.json" else s"peers/$name.json")
        val raw = read(path)("facts")("us-gaap")
        ToolFiles.array(grid("values")).map { cell =>
          val source = cell("source"); val period = cell("period")
          var row = Map[String, Json]("borrower" -> ticker.json, "entity" -> name.json, "item" -> cell("item"), "period" -> period,
            "actualMillions" -> cell("value").asBigDecimal.toString.json, "citation" -> source)
          try {
            require(source("type").asString.split('.').last == "Xbrl", "Unsupported citation type")
            val parts = source("tag").asString.split(" (?=less |plus )|(?<=less) |(?<=plus) ").toList
            val components = parts.indices.by(2).map { index =>
              val dimension = "(NetIncomeLossAvailableToCommonStockholdersBasic) \\(([^)]+)\\)".r
              val (tag, member) = parts(index) match {
                case dimension(concept, value) => concept -> Some(value)
                case concept => concept -> None
              }
              var candidates = ToolFiles.optional(raw, tag).toList.flatMap(c => ToolFiles.optional(c("units"), "USD").toList.flatMap(ToolFiles.array)).filter { fact =>
                ToolFiles.string(fact, "start") == ToolFiles.string(period, "start") && fact("end") == period("end") &&
                  Set("10-K", "10-K/A", "10-Q", "10-Q/A").contains(fact("form").asString) &&
                  (index != 0 || (fact("accn") == source("accession") && fact("form") == source("form")))
              }
              if (member.nonEmpty) candidates = Nil
              var sourceFile = path.toString
              if (candidates.isEmpty && parts.size == 1) {
                val filings = ToolFiles.array(read(sec.resolve("manifest.json"))("filings")).filter(f => f("accession") == source("accession") && f("form") == source("form") && f("role").asString.endsWith("Xbrl"))
                require(filings.size == 1, "Instance citation differs from manifest")
                val xml = sec.resolve(filings.head("file").asString)
                hashes(xml.toString) = ToolFiles.sha(Files.readAllBytes(xml))
                sourceFile = xml.toString
                val root = ToolXml.read(xml).getDocumentElement
                val children = ToolXml.children(root)
                val contexts = children.filter(_.getLocalName == "context").map(e => e.getAttribute("id") -> e).toMap
                val units = children.filter(e => e.getLocalName == "unit" && ToolXml.text(e, "measure") == "iso4217:USD").map(_.getAttribute("id")).toSet
                candidates = children.filter(e => e.getLocalName == tag && units.contains(e.getAttribute("unitRef"))).flatMap { fact =>
                  val context = contexts(fact.getAttribute("contextRef"))
                  val dimensions = ToolXml.descendants(context, "explicitMember")
                  val matches = member match {
                    case Some(value) => dimensions.size == 1 && ToolXml.local(dimensions.head.getAttribute("dimension")) == "StatementClassOfStockAxis" && ToolXml.local(dimensions.head.getTextContent.trim) == value
                    case None => ToolXml.descendants(context, "segment").isEmpty && ToolXml.descendants(context, "scenario").isEmpty
                  }
                  val end = List("endDate", "instant").map(ToolXml.text(context, _)).find(_.nonEmpty).getOrElse("")
                  Option.when(matches && ToolXml.text(context, "startDate") == ToolFiles.string(period, "start") && end == period("end").asString) {
                    Map("val" -> BigDecimal(fact.getTextContent.trim).json, "filed" -> "".json, "accn" -> source("accession")).json
                  }
                }
                row = row.updated("rawInstance", sourceFile.json)
              }
              require(candidates.nonEmpty, s"No matching raw USD fact: $tag")
              val latest = candidates.map(_("filed").asString).max
              val selected = candidates.filter(_("filed").asString == latest)
              require(selected.map(_("val").asBigDecimal).distinct.size == 1, s"Conflicting raw facts: $tag")
              val value = selected.head("val").asBigDecimal
              val sign = if (index > 0 && parts(index - 1) == "less") -1 else 1
              (value * sign, Map("tag" -> tag.json, "rawUSD" -> value.toString.json, "sign" -> sign.json,
                "accession" -> selected.head("accn"), "sourceFile" -> sourceFile.json).json)
            }.toList
            val total = components.map(_._1).sum * (if (cell("item").asString.split('.').last == "InterestExpense" && parts.head == "InterestIncomeExpenseNonoperatingNet") -1 else 1)
            val expected = total / BigDecimal(1000000)
            row = row.updated("components", components.map(_._2).json).updated("expectedMillions", expected.toString.json)
            require(expected == cell("value").asBigDecimal, s"Value mismatch: raw $expected, grid ${cell("value")}")
            row.updated("status", "verified".json).json
          } catch {
            case NonFatal(error) => row.updated("status", "unresolved".json).updated("reason", Option(error.getMessage).getOrElse(error.getClass.getSimpleName).json).json
          }
        }
      }
    }
    val unresolved = results.filter(_("status").asString != "verified")
    ToolFiles.write(output, Map("verified" -> (results.size - unresolved.size).json, "unresolved" -> unresolved.size.json,
      "values" -> results.json, "inputSHA256" -> hashes.toMap.json).json)
    println(s"${results.size - unresolved.size} verified; ${unresolved.size} unresolved")
    unresolved.foreach(r => println(s"${r("borrower").asString} ${r("entity").asString} ${r("item").asString}: ${r("reason").asString}"))
    require(unresolved.isEmpty, s"Source verification failed; inspect $output")
  }
}
