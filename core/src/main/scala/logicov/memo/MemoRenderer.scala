package logicov.memo

object MemoRenderer {
  private val LongWord = """\S{25,}""".r

  private val Binding = """\{\{([^}]+)\}\}""".r

  def render(template: MemoTemplate, context: MemoContext): String = {
    def bind(text: String): String =
      Binding.replaceAllIn(text, m => java.util.regex.Matcher.quoteReplacement(escape(context.value(m.group(1).trim))))

    val cover = template.coverStrip.map { cell =>
      val (label, value) = cell.span(_ != '|')
      s"""<div class="cell"><span class="label">${escape(label)}</span><span class="value">${bind(value.drop(1)).replace("\n", "<br>")}</span></div>"""
    }.mkString
    val sections = template.sections.zipWithIndex.map { (section, index) =>
      val blocks = section.blocks.zipWithIndex.map {
        case (MemoBlock.Subheading(text), _) => s"<h3>${bind(text)}</h3>"
        case (MemoBlock.Paragraph(text), _) => Some(bind(text).trim).filter(_.nonEmpty).fold("")(t => s"<p>$t</p>")
        case (MemoBlock.Table(dataset), _) if context.table(dataset).rows.isEmpty => ""
        case (MemoBlock.Table(dataset), _) => table(dataset, context.table(dataset)) +
          s"""<p class="evidence"><a href="${escape(context.value("source.audit"))}#$dataset">Calculation and source detail</a></p>"""
        case (MemoBlock.Narrative(author, guidance), blockIndex) =>
          context.narratives.get(narrativeKey(index, blockIndex)) match {
            case Some(narrative) => written(author, narrative, section.title.contains("Risk Factors")) +
              s"""<p class="evidence"><a href="${escape(context.value("source.audit"))}#narrative-${narrativeKey(index, blockIndex)}">Sources for this section</a></p>"""
            case None => s"""<div class="narrative"><span class="label">Narrative · ${escape(author)}</span><p>${bind(guidance)}</p></div>"""
          }
      }.mkString("\n")
      s"<section><h2>${index + 1}. ${escape(section.title)}</h2>\n$blocks</section>"
    }.mkString("\n")
    s"""<!doctype html>
       |<html lang="en"><head><meta charset="utf-8"><title>${bind(template.title)}</title><style>${Style}</style></head>
       |<body>
       |<header><div>${bind(template.header)}</div><h1>${bind(template.title)}</h1><div>${bind(template.footer)}</div></header>
       |<div class="cover">$cover</div>
       |$sections
       |<p class="disclaimer">${bind(template.disclaimer)}</p>
       |<footer>${bind(template.footer)}</footer>
       |</body></html>""".stripMargin
  }

  def audit(context: MemoContext, sourceLinks: List[(String, String)]): String = {
    val details = context.tables.toList.sortBy(_._1).map((key, value) => table(key, value.copy(links = Map.empty))).mkString("\n")
    val evidence = context.values.toList.filter(_._1.startsWith("source-document.")).sortBy(_._1)
      .map((key, value) => s"<h2>${escape(key)}</h2><pre>${escape(value)}</pre>").mkString
    val narratives = context.narratives.toList.sortBy(_._1).map((key, n) =>
      s"""<section id="narrative-$key"><h2>Narrative $key</h2><p>Draft narrative from the deal record and retained source documents. Numeric matching is not factual or analyst approval.</p>""" +
        (if (n.fromTranscript.isEmpty) "" else s"""<p class="note">Figures found only in the discovery transcript (confirm against filings): ${escape(n.fromTranscript.mkString(", "))}</p>""") +
        s"""<p>${escape(n.text)}</p></section>""").mkString
    val files = sourceLinks.map((name, link) => s"""<li><a href="${escape(link)}">${escape(name)}</a></li>""").mkString
    s"""<!doctype html><html lang="en"><head><meta charset="utf-8"><title>Source and calculation audit</title><style>$Style pre {white-space:pre-wrap} table {break-inside:auto}</style></head>
       |<body><h1>${escape(context.value("borrower"))}: source and calculation audit</h1>
       |<p>Retained evidence supports the historical as-of dates in this memo; it is not a refresh to today's financial position. This companion must accompany the memo for its links to work.</p>
       |<h2>Retained source files</h2><ul>$files</ul>$details$evidence$narratives</body></html>""".stripMargin
  }

  def narrativeKey(section: Int, block: Int): String = s"$section.$block"

  private def written(author: String, narrative: NarrativeText, risks: Boolean): String = {
    val parts = narrative.text.split("\\n\\s*\\n").filter(_.trim.nonEmpty)
    val paragraphs = if (risks) parts.zipWithIndex.map((p, i) => s"<li id=\"risk-$i\">${escape(p.trim).replace("**", "").replace("Risk:", "<strong>Risk:</strong>").replace("Mitigant:", "<strong>Mitigant:</strong>")}</li>").mkString("<ol class=\"risks\">", "", "</ol>")
      else parts.map { p =>
        val lines = p.trim.linesIterator.map(_.trim).filter(_.nonEmpty).toList
        def bullet(line: String): Boolean = line.startsWith("- ") || line.startsWith("* ")
        val (intro, items) = lines.span(line => !bullet(line))
        if (items.nonEmpty && items.forall(bullet)) {
          val lead = if (intro.isEmpty) "" else s"<p>${escape(intro.mkString(" "))}</p>"
          lead + items.map(line => s"<li>${escape(line.drop(2))}</li>").mkString("<ul>", "", "</ul>")
        } else s"<p>${escape(p.trim).replace("**", "")}</p>"
      }.mkString
    val warning = if (narrative.unverified.isEmpty) "" else
      s"""<p class="note">Figures not found in the record (verify before release): ${escape(narrative.unverified.mkString(", "))}</p>"""
    s"""<div class="written">$paragraphs$warning</div>"""
  }

  private def table(dataset: String, t: MemoTable): String = {
    val financial = Set("financials", "income-statement", "balance-sheet", "cash-flow", "cash-movements", "debt", "key-ratios", "segments", "peers")
    val widths = if (financial.contains(dataset) && t.headers.size > 1) {
      42.0 :: List.fill(t.headers.size - 1)(58.0 / (t.headers.size - 1))
    } else dataset match {
      case "leadership" => List(18.0, 27.0, 7.0, 48.0)
      case "scorecard" => List(22.0, 48.0, 8.0, 10.0, 12.0)
      case "rating-map" => List(14.0, 11.0, 13.0, 14.0, 26.0, 22.0)
      case _ => Nil
    }
    val columns = if (widths.size == t.headers.size && widths.nonEmpty)
      widths.map(w => s"""<col style="width:$w%">""").mkString("<colgroup>", "", "</colgroup>") else ""
    val layout = if (columns.isEmpty) "" else " fixed-columns"
    val head = t.headers.map(h => s"<th>${escape(h)}</th>").mkString
    val rows = t.rows.zipWithIndex.map { (row, r) =>
      val cells = row.zipWithIndex.map { (value, c) =>
        val content = t.links.get((r, c)).fold(cell(value))(link => s"""<a href="${escape(link)}">${cell(value)}</a>""")
        val styled = if (t.boldCells.contains((r, c))) s"<strong>$content</strong>" else content
        s"<td>$styled</td>"
      }.mkString
      val id = t.rowIds.get(r).fold("")(id => s""" id="$id"""" )
      s"<tr$id>$cells</tr>"
    }.mkString
    val note = t.note.fold("")(n => s"""<p class="note">${escape(n)}</p>""")
    s"""<table id="$dataset" class="$dataset$layout"><caption>${escape(t.title)}</caption>$columns<thead><tr>$head</tr></thead><tbody>$rows</tbody></table>$note"""
  }

  /** Lets long XBRL names break between words and URLs break after slashes, so no column forces the table wide. */
  private def cell(text: String): String = LongWord.replaceAllIn(escape(text), m =>
    scala.util.matching.Regex.quoteReplacement(m.matched.replaceAll("([a-z])([A-Z])", "$1<wbr>$2").replace("/", "/<wbr>"))
  )

  private def escape(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

  private val Style: String =
    """@page { size: Letter; margin: 0.55in 0.6in 0.6in;
      |  @bottom-center { content: "Page " counter(page) " of " counter(pages); font-family: Arial, sans-serif; font-size: 7.5pt; color: #55636e; }
      |}
      |body { font-family: "Helvetica Neue", Helvetica, Arial, sans-serif; font-size: 9.2pt; line-height: 1.38; color: #1f2a33; margin: 0; }
      |header { border-bottom: 3px solid #0a3d61; padding-bottom: 8px; margin-bottom: 10px; }
      |header div { color: #2b8aba; font-weight: 600; font-size: 8.5pt; letter-spacing: .04em; }
      |h1 { font-size: 17pt; color: #0a3d61; margin: 4px 0 0; }
      |h2 { font-size: 11pt; color: #0a3d61; border-left: 4px solid #2b8aba; padding-left: 8px; margin: 18px 0 6px; break-after: avoid; }
      |h3 { font-size: 9.5pt; color: #0a3d61; margin: 12px 0 4px; break-after: avoid; }
      |.cover { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); break-inside: avoid; border: 1px solid #d6e2ea; margin-bottom: 8px; }
      |.cover .cell { padding: 6px 8px; border-right: 1px solid #d6e2ea; background: #eef6fb; }
      |.cover .cell:nth-child(3n) { border-right: 0; }
      |.cover .cell:nth-child(n+4) { border-top: 1px solid #d6e2ea; }
      |.label { display: block; font-size: 7pt; font-weight: 700; letter-spacing: .08em; text-transform: uppercase; color: #2b8aba; }
      |.value { font-weight: 600; color: #0a3d61; }
      |table { width: 100%; border-collapse: collapse; margin: 6px 0 2px; break-inside: auto; }
      |tr { break-inside: avoid; }
      |td { vertical-align: top; }
      |table.fixed-columns { table-layout: fixed; }
      |table.financials { break-inside: avoid; }
      |table.financial-covenant-components td, table.financial-covenant-components th { text-align: left; }
      |table.financial-covenant-components td:nth-child(2), table.financial-covenant-components th:nth-child(2) { text-align: right; }
      |thead { break-after: avoid; }
      |table.stress, table.peers, section:last-of-type { break-inside: avoid; }
      |caption { text-align: left; font-size: 7.5pt; font-weight: 700; letter-spacing: .06em; color: #0a3d61; padding-bottom: 3px; break-after: avoid; }
      |th { background: #0a3d61; color: #fff; font-size: 7.5pt; text-align: left; padding: 4px 6px; }
      |td { border-bottom: 1px solid #d6e2ea; padding: 3px 6px; }
      |table.sources td, table.filings td { font-size: 6.5pt; padding: 1px 4px; }
      |table.sources td:nth-child(2) { white-space: nowrap; }
      |td:not(:first-child), th:not(:first-child) { text-align: right; }
      |.note { font-size: 7.5pt; color: #55636e; margin: 2px 0 8px; }
      |.narrative { border: 1px dashed #2b8aba; background: #f6fafd; padding: 6px 10px; margin: 8px 0; color: #55636e; }
      |.written { margin: 8px 0; }
      |.evidence { font-size: 7pt; margin: 2px 0 8px; }
      |a { color: #0a3d61; text-decoration: none; }
      |.risks li { margin: 0 0 10px; padding-left: 4px; break-inside: avoid; }
      |table.facility :is(td, th), table.outlook :is(td, th), table.analyst-inputs :is(td, th), table.funded-debt :is(td, th), table.leadership :is(td, th) { text-align: left; }
      |table.stress { font-size: 8pt; }
      |table.rating-map { break-inside: avoid; }
      |.disclaimer { font-size: 7.5pt; color: #55636e; margin-top: 18px; }
      |@media print { footer { display: none; } }
      |footer { font-size: 7.5pt; color: #55636e; border-top: 1px solid #d6e2ea; margin-top: 6px; padding-top: 4px; }""".stripMargin
}
