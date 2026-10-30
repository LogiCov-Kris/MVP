package logicov.workspace

import fabric.io.JsonParser
import fabric.rw.*
import logicov.analysis.CreditAnalysis
import logicov.memo.{MemoBlock, MemoContext, MemoRenderer, MemoTemplate, NarrativeText}

import java.nio.charset.StandardCharsets
import java.time.LocalDate
import scala.collection.mutable
import scala.util.matching.Regex

case class WorkspaceDraft(template: MemoTemplate, context: MemoContext, texts: Map[String, String], supplement: String) {
  def review: MemoReviewDocument = {
    val sections = template.sections.filter(s => WorkspaceDraft.sectionTitles.contains(s.title)).zipWithIndex.map { (section, index) =>
      val local = context.copy(narratives = Map(MemoRenderer.narrativeKey(0, 0) -> context.narratives(MemoRenderer.narrativeKey(index, 0))))
      val rendered = MemoRenderer.render(template.copy(sections = List(section)), local)
      val html = rendered.substring(rendered.indexOf("<section>"), rendered.lastIndexOf("</section>") + 10)
      MemoReviewSection(section.title, texts(section.title), html.replace("<h2>1.", s"<h2>${index + 1}."))
    }
    val audit = MemoRenderer.audit(context, Nil)
    val appendix = audit.substring(audit.indexOf("<body>") + 6, audit.indexOf("</body>"))
      .replace("id=\"", "id=\"evidence-") + supplement
    val full = MemoRenderer.render(template, context)
    val cover = full.substring(full.indexOf("<body>") + 6, full.indexOf("<section>"))
    MemoReviewDocument(sections, appendix, context.values.filterNot((key, _) => key.startsWith("source") || key == "reference"), cover)
  }
}

object WorkspaceDraft {
  private lazy val source: MemoTemplate = {
    val stream = getClass.getClassLoader.getResourceAsStream("templates/public-revolver.json")
    try JsonParser(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).as[MemoTemplate] finally stream.close()
  }
  def sectionTitles: List[String] = source.sections.map(_.title).filterNot(_ == "Source Appendix")
  private val Binding = "\\{\\{([^}]+)\\}\\}".r

  def bind(text: String, values: Map[String, String]): String = Binding.replaceAllIn(text, m =>
    Regex.quoteReplacement(values.getOrElse(m.group(1).trim, throw new IllegalArgumentException(s"Unknown shared value: ${m.group(1)}"))))

  def validateText(deal: WorkspaceDeal, section: String, text: String): Unit = {
    val draft = prepare(deal.copy(narratives = deal.narratives.updated(section, text)))
    bind(text, draft.context.values)
    ()
  }

  def prepare(deal: WorkspaceDeal, drafts: Map[String, NarrativeText] = Map.empty): WorkspaceDraft = {
    val record = deal.record.getOrElse(throw new IllegalArgumentException("Complete the credit profile to preview the credit memo"))
    val base = MemoContext.build(CreditAnalysis.run(record), LocalDate.now(), deal.id)
    val values = base.values.updated("recommendation", deal.recommendation.label).updated("recommendation.basis", deal.recommendationBasis)
      .updated("source.audit", "")
    val selected = source.sections.filter(s => WorkspaceDraft.sectionTitles.contains(s.title))
    val texts = selected.map { section =>
      val default = if (section.title == "Recommendation") "{{recommendation}}. {{recommendation.basis}}"
        else (section.blocks.collect { case MemoBlock.Paragraph(text) => text } :+ fallback(section.title)).filter(_.nonEmpty).mkString("\n\n")
      section.title -> deal.narratives.getOrElse(section.title, drafts.get(section.title).fold(default)(_.text))
    }.toMap
    val resolved = mutable.Map.empty[String, String]
    def resolve(title: String, visited: Set[String]): String = {
      require(!visited.contains(title), "Shared narrative references cannot form a cycle")
      if (resolved.contains(title)) return resolved(title)
      val output = new StringBuilder
      var offset = 0
      Binding.findAllMatchIn(texts(title)).foreach { m =>
        val key = m.group(1).trim
        val value = if (key.startsWith("narrative.")) {
          val target = key.stripPrefix("narrative.")
          require(texts.contains(target), s"Unknown shared narrative: $target")
          resolve(target, visited + title)
        } else values.getOrElse(key, throw new IllegalArgumentException(s"Unknown shared value: $key"))
        require(output.length + m.start - offset + value.length <= 100000, "Shared narrative exceeds the document size limit")
        output.append(texts(title).substring(offset, m.start)).append(value)
        offset = m.end
      }
      output.append(texts(title).substring(offset))
      require(output.length <= 100000, "Shared narrative exceeds the document size limit")
      val result = output.toString
      resolved.update(title, result)
      result
    }
    val shared = texts.keys.map(title => ("narrative." + title) -> resolve(title, Set.empty)).toMap
    val enriched = values ++ shared
    val sections = selected.map(s => s.copy(blocks = MemoBlock.Narrative("Analyst", "") :: s.blocks.filter {
      case MemoBlock.Table(_) | MemoBlock.Subheading(_) => true
      case _ => false
    }))
    val narratives = sections.zipWithIndex.map { (section, index) =>
      MemoRenderer.narrativeKey(index, 0) -> drafts.getOrElse(section.title, NarrativeText("", Nil)).copy(text = bind(texts(section.title), enriched))
    }.toMap
    val tables = base.tables.view.mapValues(t => t.copy(links = t.links.view.mapValues { link =>
      if (link.contains("#")) "#evidence-" + link.split("#", 2).last else link
    }.toMap)).toMap
    WorkspaceDraft(source.copy(title = "Credit Memorandum | {{borrower}}", header = "Credit Memorandum · CONFIDENTIAL",
      sections = sections, disclaimer = source.disclaimer.replace("Sample output for analyst review", "Prepared for analyst review")),
      base.copy(values = enriched, tables = tables, narratives = narratives), texts, supplement(deal))
  }

  private def escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

  def supplement(deal: WorkspaceDeal): String = {
    val policyRows = deal.policyResults.map { r =>
      s"<tr><td>${escape(r.description)}</td><td>${r.status}</td><td>${r.actual.fold("Not available")(_.toString)}</td><td>${r.threshold.fold("")(_.toString)}</td></tr>"
    }.mkString
    val policy = s"""<section><h2>Policy evaluation</h2><p>${escape(deal.policy.fold("No institution policy configured")(p => s"${p.name}, version ${p.version}, source: ${p.source}"))}</p>
      <table><tr><th>Rule</th><th>Result</th><th>Actual</th><th>Threshold</th></tr>$policyRows</table>
      <p>Approval exception: ${escape(deal.approval.fold("")(_.exception))}</p></section>"""
    val checks = deal.stopReviews.map(r => s"<tr><td>${escape(r.topic.label)}</td><td>${r.status}</td><td>${escape(r.evidence)}</td></tr>").mkString
    val approval = s"""<section><h2>Analyst approval</h2><p>${escape(deal.approval.fold("")(a => s"${a.actor}, ${a.at}; reviewed revision ${a.revision}"))}</p>
      <h3>Required analyst checks</h3><table><tr><th>Check</th><th>Result</th><th>Evidence</th></tr>$checks</table>
      <p>Approval records input review. This memo is not a commitment to lend.</p></section>"""
    val submission = deal.submission.fold("")(s => s"<section><h2>Submitted for Approval</h2><p>${escape(s.actor)} · ${escape(s.at)}</p><p>${escape(s.recommendation.label)}: ${escape(s.rationale)}</p></section>")
    policy + approval + submission
  }

  private def fallback(section: String): String = section match {
    case "Executive Summary" => "{{borrower}} has a final risk rating of {{rating.mapping}}. The analysis uses retained financial statements and documented analyst inputs."
    case "Financial Analysis" => "The financial spread below identifies the periods, amounts and sources used. Review the historical trends and repayment capacity alongside the documented sensitivities."
    case "Stress Testing" => "The stress results use the scenarios and actual covenant definition recorded for this facility. These sensitivities are calculations rather than forecasts."
    case _ => ""
  }
}
