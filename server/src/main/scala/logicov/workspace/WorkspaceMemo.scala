package logicov.workspace

import fabric.io.{JsonFormatter, JsonParser}
import fabric.rw.*
import logicov.memo.{MemoRenderer, NarrativeText, PdfPrinter}
import logicov.model.SessionView

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.zip.{ZipEntry, ZipOutputStream}
import scala.jdk.CollectionConverters.*

object WorkspaceMemo {
  private def escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

  def generate(session: SessionView, deal: WorkspaceDeal, store: WorkspaceStore = WorkspaceService.store, progress: GenerationProgress => Unit = _ => ()): List[(WorkspaceDocument, Array[Byte])] = {
    StopReview.requireClear(deal.stopReviews)
    val directory = Files.createTempDirectory("logicov-memo-")
    try {
      val base = sys.env.getOrElse("LOGICOV_PUBLIC_URL", "http://127.0.0.1:18300").stripSuffix("/")
      require(base.startsWith("https://") || (!sys.env.get("LOGICOV_ENV").contains("production") && base.startsWith("http://127.0.0.1:")), "A valid public application URL is required")
      val sources = deal.documents.filterNot(_.name.startsWith("generated-"))
      val links = sources.map(d => d.name -> s"$base/api/deals/${deal.id}/files/${d.id}")
      val auditId = new WorkspaceWorkflow(store).identifier()
      val auditUrl = s"$base/api/deals/${deal.id}/files/$auditId"
      var generatedTexts = deal.narratives
      var generatedEvidence = Map.empty[String, NarrativeText]
      var renderedSections = List.empty[String]
      val (body, audit) = deal.record match {
        case Some(record) =>
          val drafts = WorkspaceNarratives.generate(deal, directory.resolve("narrative-cache"), progress)
          generatedEvidence = deal.generatedNarratives ++ drafts
          val draft = WorkspaceDraft.prepare(deal, drafts)
          generatedTexts = draft.texts
          val context = draft.context
          val appendix = draft.review.appendix
          val sourceList = links.map((name, url) => s"<p><a href=\"${escape(url)}\">${escape(name)}</a></p>").mkString
          val html = MemoRenderer.render(draft.template, context, title => {
            renderedSections = renderedSections :+ title
            progress(GenerationProgress(GenerationPhase.Rendering, renderedSections, WorkspaceDraft.sectionTitles))
          })
            .replace("href=\"#", "href=\"#evidence-")
            .replace("#evidence-evidence-", "#evidence-")
            .replace("</body>", s"<section id=\"citations\"><h2>Source Appendix</h2>$sourceList$appendix</section></body>")
          html -> MemoRenderer.audit(context, links)
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
      val memoHtml = if (deal.record.nonEmpty) body else body.replace("</body>", WorkspaceDraft.supplement(deal) + "</body>")
      val html = directory.resolve("memo.html")
      Files.writeString(html, memoHtml)
      val printer = new PdfPrinter(sys.env.getOrElse("LOGICOV_CHROMIUM", "/usr/local/bin/chromium"))
      progress(GenerationProgress(GenerationPhase.Printing, renderedSections, WorkspaceDraft.sectionTitles))
      val pdf = printer.print(html, directory.resolve("memo.pdf"))
      val pdfBytes = Files.readAllBytes(pdf)
      val pdfMeta = new WorkspaceWorkflow(store).document(s"generated-memo-r${deal.revision}.pdf", "application/pdf", pdfBytes)
      val auditMeta = new WorkspaceWorkflow(store).document(s"generated-audit-r${deal.revision}.html", "text/html", auditBytes).copy(id = auditId)
      val htmlBytes = memoHtml.getBytes(StandardCharsets.UTF_8)
      val htmlMeta = new WorkspaceWorkflow(store).document(s"generated-memo-r${deal.revision}.html", "text/html", htmlBytes)
      progress(GenerationProgress(GenerationPhase.Packaging, renderedSections, WorkspaceDraft.sectionTitles))
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
      val narrativeBytes = JsonFormatter.Compact(generatedTexts.json).getBytes(StandardCharsets.UTF_8)
      val evidenceBytes = JsonFormatter.Compact(generatedEvidence.json).getBytes(StandardCharsets.UTF_8)
      Files.write(directory.resolve("generated-narratives.json"), narrativeBytes)
      Files.write(directory.resolve("generated-narrative-evidence.json"), evidenceBytes)
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
      val narrativeMeta = new WorkspaceWorkflow(store).document("generated-narratives.json", "application/json", narrativeBytes)
      val evidenceMeta = new WorkspaceWorkflow(store).document("generated-narrative-evidence.json", "application/json", evidenceBytes)
      List(evidenceMeta -> evidenceBytes, pdfMeta -> pdfBytes, auditMeta -> auditBytes, htmlMeta -> htmlBytes, zipMeta -> zipBytes, narrativeMeta -> narrativeBytes)
    } finally {
      val files = Files.walk(directory)
      try files.iterator().asScala.toList.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists)
      finally files.close()
    }
  }

}
