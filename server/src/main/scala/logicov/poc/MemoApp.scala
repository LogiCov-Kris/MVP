package logicov.poc

import fabric.io.JsonParser
import fabric.rw.*
import logicov.ai.LogiCovSigil
import logicov.analysis.CreditAnalysis
import logicov.deal.DealRecord
import logicov.memo.{MemoContext, MemoRenderer, MemoTemplate, NarrativeGenerator, PdfPrinter}
import spice.http.client.HttpClient

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption}
import java.time.LocalDate
import scala.jdk.CollectionConverters.*

object MemoApp {
  private val TemplateResource = "templates/public-revolver.json"
  private val NarrativesFlag = "--narratives"

  def main(args: Array[String]): Unit = {
    val (flags, positional) = args.toList.partition(_.startsWith("--"))
    if (positional.size < 2) throw new IllegalArgumentException(s"Usage: MemoApp <deal.json> <output dir> [chrome] [$NarrativesFlag] [--source-pack=<directory>] [--narratives-from=<json>]")
    val record = JsonParser(Files.readString(Path.of(positional.head))).as[DealRecord]
    val output = Files.createDirectories(Path.of(positional(1)))
    val chrome = positional.lift(2).getOrElse("chromium")
    val template = JsonParser(resource(TemplateResource)).as[MemoTemplate]
    val date = LocalDate.now()
    val base = MemoContext.build(CreditAnalysis.run(record), date, s"${record.ticker}-$date")
    val retained = flags.find(_.startsWith("--narratives-from=")).map(f => Path.of(f.stripPrefix("--narratives-from=")))
    require(!(retained.isDefined && flags.contains(NarrativesFlag)), "Choose retained narratives or new generation")
    val context = if (retained.isDefined) {
      val drafts = JsonParser(Files.readString(retained.getOrElse(throw new IllegalStateException("Missing narrative path")))).as[Map[String, String]]
      base.copy(narratives = NarrativeGenerator.reuse(template, base, drafts))
    } else if (flags.contains(NarrativesFlag)) {
      LogiCovSigil.instance.sync()
      try base.copy(narratives = NarrativeGenerator.generate(template, base, record.inputs.narrativeNotes,
        output.resolve("narrative-cache").resolve(record.ticker.toLowerCase)).sync())
      finally {
        try LogiCovSigil.shutdown.sync()
        finally HttpClient.dispose().sync()
      }
    } else {
      base
    }
    val name = record.ticker.toLowerCase
    val sourcePack = flags.find(_.startsWith("--source-pack=")).map(f => Path.of(f.stripPrefix("--source-pack=")))
    val sourceLinks = sourcePack.toList.flatMap { pack =>
      val destination = output.resolve(s"$name-sources")
      val files = Files.walk(pack)
      try files.iterator().asScala.filter(Files.isRegularFile(_)).map { file =>
        val relative = pack.relativize(file)
        val target = destination.resolve(relative)
        Files.createDirectories(target.getParent)
        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING)
        relative.toString -> s"$name-sources/${relative.toString}"
      }.toList.sortBy(_._1)
      finally files.close()
    }
    Files.writeString(output.resolve(s"$name-audit.html"), MemoRenderer.audit(context, sourceLinks))
    val html = Files.writeString(output.resolve(s"$name-memo.html"), MemoRenderer.render(template, context))
    val pdf = new PdfPrinter(chrome).print(html, output.resolve(s"$name-memo.pdf"))
    scribe.info(s"${record.borrower}: $html, $pdf")
  }

  private def resource(path: String): String =
    new String(getClass.getClassLoader.getResourceAsStream(path).readAllBytes(), StandardCharsets.UTF_8)
}
