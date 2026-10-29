package logicov.workspace

import fabric.io.{JsonFormatter, JsonParser}
import fabric.rw.*
import logicov.analysis.CreditAnalysis
import logicov.memo.{MemoBlock, MemoContext, MemoRenderer, MemoTemplate, NarrativeGenerator, NarrativeText, PdfPrinter}
import logicov.model.SessionView

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.LocalDate
import java.util.zip.{ZipEntry, ZipOutputStream}
import scala.jdk.CollectionConverters.*

object WorkspaceMemo {
  private def escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

  def generate(session: SessionView, deal: WorkspaceDeal, store: WorkspaceStore = WorkspaceService.store): List[(WorkspaceDocument, Array[Byte])] = {
    val directory = Files.createTempDirectory("logicov-memo-")
    try {
      val base = sys.env.getOrElse("LOGICOV_PUBLIC_URL", "http://127.0.0.1:18300").stripSuffix("/")
      require(base.startsWith("https://") || (!sys.env.get("LOGICOV_ENV").contains("production") && base.startsWith("http://127.0.0.1:")), "A valid public application URL is required")
      val sources = deal.documents.filterNot(_.name.startsWith("generated-"))
      val links = sources.map(d => d.name -> s"$base/api/deals/${deal.id}/files/${d.id}")
      val auditId = new WorkspaceWorkflow(store).identifier()
      val auditUrl = s"$base/api/deals/${deal.id}/files/$auditId"
      val policyRows = deal.policyResults.map { r =>
        s"<tr><td>${escape(r.description)}</td><td>${r.status}</td><td>${r.actual.fold("Not available")(_.toString)}</td><td>${r.threshold.fold("")(_.toString)}</td></tr>"
      }.mkString
      val policy = s"""<section><h2>Policy evaluation</h2><p>${escape(deal.policy.fold("No institution policy configured")(p => s"${p.name}, version ${p.version}, source: ${p.source}"))}</p>
        <table><tr><th>Rule</th><th>Result</th><th>Actual</th><th>Threshold</th></tr>$policyRows</table>
        <p>Approval exception: ${escape(deal.approval.fold("")(_.exception))}</p></section>"""
      val approval = s"""<section><h2>Analyst approval</h2><p>${escape(deal.approval.fold("")(a => s"${a.actor}, ${a.at}; reviewed revision ${a.revision}"))}</p>
        <p>Approval records input review. This memo is not a commitment to lend.</p></section>"""
      val (body, audit) = deal.record match {
        case Some(record) =>
          val stream = getClass.getClassLoader.getResourceAsStream("templates/public-revolver.json")
          val baseTemplate = try JsonParser(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).as[MemoTemplate] finally stream.close()
          val template = baseTemplate.copy(title = "Credit Memorandum | {{borrower}}", header = "Credit Memorandum · CONFIDENTIAL",
            disclaimer = baseTemplate.disclaimer.replace("Sample output for analyst review", "Prepared for analyst review"),
            sections = baseTemplate.sections.map { section =>
              if (section.title != "Recommendation") section
              else section.copy(blocks = section.blocks.map {
                case MemoBlock.Paragraph(_) => MemoBlock.Paragraph("{{recommendation}}. {{recommendation.basis}}")
                case block => block
              })
            })
          val analysis = CreditAnalysis.run(record)
          val baseContext = MemoContext.build(analysis, LocalDate.now(), deal.id)
          val context = baseContext.copy(values = baseContext.values.updated("recommendation", deal.recommendation.label)
            .updated("recommendation.basis", deal.recommendationBasis))
          val configured = sys.env.get("LOGICOV_NARRATIVES_ENABLED").contains("true")
          val drafts = if (configured) {
            require(sys.env.get("LOGICOV_PROVIDER_APPROVED").contains("true"), "A client-owned, zero-retention provider account must be approved before workspace narrative generation")
            NarrativeGenerator.generate(template, context, record.inputs.narrativeNotes, directory.resolve("narrative-cache")).sync()
          } else Map.empty
          val narratives = template.sections.zipWithIndex.flatMap { (section, sectionIndex) =>
            val first = section.blocks.indexWhere {
              case MemoBlock.Narrative(_, _) => true
              case _ => false
            }
            section.blocks.zipWithIndex.collect { case (MemoBlock.Narrative(_, _), blockIndex) =>
              val key = MemoRenderer.narrativeKey(sectionIndex, blockIndex)
              val narrative = if (configured && !deal.narratives.contains(section.title)) drafts(key)
                else NarrativeText(if (blockIndex == first) deal.narratives.getOrElse(section.title, fallback(section.title, analysis)) else "", Nil)
              key -> narrative
            }
          }.toMap
          val auditPath = context.value("source.audit")
          val tables = context.tables.view.mapValues { table =>
            table.copy(links = table.links.view.mapValues(link => if (link.startsWith(auditPath + "#")) auditUrl + link.stripPrefix(auditPath) else link).toMap)
          }.toMap
          val enriched = context.copy(values = context.values.updated("source.audit", auditUrl), tables = tables, narratives = narratives)
          MemoRenderer.render(template, enriched) -> MemoRenderer.audit(enriched, links)
        case None =>
          val rows = deal.grid.values.map { v => s"<tr><td>${escape(v.item.toString)}</td><td>${escape(v.period.label)}</td><td>${v.value}</td><td>${escape(v.source.describe)}</td></tr>" }.mkString
          val html = s"""<!doctype html><html><head><meta charset="utf-8"><style>body{font:12px sans-serif;margin:40px;color:#203344}h1,h2{color:#142b3c}table{width:100%;border-collapse:collapse}td,th{padding:8px;border-bottom:1px solid #ddd;text-align:left}tr{break-inside:avoid}</style></head><body>
            <h1>${escape(deal.title)}: Financial review</h1><p>Amounts are USD millions. A complete credit profile has not been supplied; credit ratios and a credit recommendation are not asserted.</p>
            <table><tr><th>Line item</th><th>Period</th><th>Amount</th><th>Citation</th></tr>$rows</table>
            <p><a href="$auditUrl">Source documents and audit history</a></p></body></html>"""
          html -> s"<html><body><h1>Retained source documents</h1>${links.map((name, url) => s"<p><a href=\"${escape(url)}\">${escape(name)}</a></p>").mkString}</body></html>"
      }
      val history = deal.events.map(e => s"<tr><td>${escape(e.at)}</td><td>${escape(e.actor)}</td><td>${escape(e.action)}</td><td>${escape(e.detail)}</td></tr>").mkString
      val auditBytes = audit.replace("</body>", s"<h2>Review history</h2><table>$history</table></body>").getBytes(StandardCharsets.UTF_8)
      val memoHtml = body.replace("</body>", policy + approval + "</body>")
      val html = directory.resolve("memo.html")
      Files.writeString(html, memoHtml)
      val printer = new PdfPrinter(sys.env.getOrElse("LOGICOV_CHROMIUM", "/usr/local/bin/chromium"))
      val pdf = printer.print(html, directory.resolve("memo.pdf"))
      val pdfBytes = Files.readAllBytes(pdf)
      val pdfMeta = new WorkspaceWorkflow(store).document(s"generated-memo-r${deal.revision}.pdf", "application/pdf", pdfBytes)
      val auditMeta = new WorkspaceWorkflow(store).document(s"generated-audit-r${deal.revision}.html", "text/html", auditBytes).copy(id = auditId)
      val htmlBytes = memoHtml.getBytes(StandardCharsets.UTF_8)
      val htmlMeta = new WorkspaceWorkflow(store).document(s"generated-memo-r${deal.revision}.html", "text/html", htmlBytes)
      Files.write(directory.resolve("audit.html"), auditBytes)
      var portableAudit = new String(auditBytes, StandardCharsets.UTF_8)
      sources.foreach { source =>
        val (_, bytes) = store.file(session.tenantId, deal.id, source.id)
        val filename = s"source-${source.id}-${Path.of(source.name).getFileName}"
        Files.write(directory.resolve(filename), bytes)
        portableAudit = portableAudit.replace(s"$base/api/deals/${deal.id}/files/${source.id}", filename)
      }
      Files.writeString(directory.resolve("audit.html"), portableAudit)
      Files.writeString(html, memoHtml.replace(auditUrl, "audit.html"))
      printer.print(html, pdf)
      Files.writeString(directory.resolve("approved-workspace.json"), JsonFormatter.Default(deal.json))
      val entries = Files.list(directory)
      val hashes = try entries.iterator().asScala.filter(Files.isRegularFile(_)).map { path =>
        path.getFileName.toString -> new WorkspaceWorkflow(store).sha(Files.readAllBytes(path))
      }.toMap finally entries.close()
      Files.writeString(directory.resolve("SHA256SUMS.json"), JsonFormatter.Default(hashes.json))
      val zip = directory.resolve("review.zip")
      val output = new ZipOutputStream(Files.newOutputStream(zip))
      val files = Files.list(directory)
      try files.iterator().asScala.filter(p => p != zip && Files.isRegularFile(p)).foreach { file =>
        output.putNextEntry(new ZipEntry(file.getFileName.toString))
        Files.copy(file, output)
        output.closeEntry()
      } finally { files.close(); output.close() }
      val zipBytes = Files.readAllBytes(zip)
      val zipMeta = new WorkspaceWorkflow(store).document(s"generated-review-r${deal.revision}.zip", "application/zip", zipBytes)
      List(pdfMeta -> pdfBytes, auditMeta -> auditBytes, htmlMeta -> htmlBytes, zipMeta -> zipBytes)
    } finally {
      val files = Files.walk(directory)
      try files.iterator().asScala.toList.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists)
      finally files.close()
    }
  }

  private def fallback(title: String, analysis: CreditAnalysis): String = {
    val ratios = analysis.ratios
    title.toLowerCase match {
      case t if t.contains("executive") => s"${analysis.record.borrower} has a calculated risk rating of ${analysis.rating.machineGrade.label} and a final risk rating of ${analysis.rating.grade.label}. The analysis uses the retained financial statements and documented analyst inputs."
      case t if t.contains("financial") => s"The financial spread uses EBITDA of ${ratios.ebitda} million and net debt of ${ratios.netDebt} million. Net leverage is ${ratios.netLeverage.setScale(2, BigDecimal.RoundingMode.HALF_UP)}x. The tables identify the periods and sources used."
      case t if t.contains("covenant") => s"The facility test is ${analysis.covenant.covenant.kind}, with a threshold of ${analysis.covenant.threshold} and a calculated result of ${analysis.covenant.actual.setScale(4, BigDecimal.RoundingMode.HALF_UP)}. Contractual definitions and compliance reporting govern the facility test."
      case t if t.contains("risk rating") => s"The calculated composite is ${analysis.rating.composite}. The final risk rating is ${analysis.rating.grade.mapping}; documented overrides remain part of the analyst record."
      case t if t.contains("stress") => "The stress results use the scenarios and actual covenant definition recorded for this facility. These sensitivities are calculations rather than forecasts."
      case t if t.contains("recommendation") => "The analyst has approved the inputs for this memo. The credit recommendation requires the analyst's documented judgment and the institution's approval process."
      case _ => ""
    }
  }
}
