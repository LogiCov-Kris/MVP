package logicov.ui

import everyui.component.{Button, Column, Component, Container, Row, Select, TextInput, TextView}
import everyui.component.types.{Semantics, TextAlign, WhiteSpace}
import everyui.layout.{HorizontalLayout, VerticalLayout}
import everyui.table.{GridColumn, VirtualTable}
import everyui.theme.Theme
import fabric.io.JsonParser
import fabric.rw.*
import logicov.analysis.{CovenantKind, CreditAnalysis, StressScenario}
import logicov.grid.GridCorrection
import logicov.rating.Support
import logicov.source.Sourced
import logicov.workspace.*
import org.scalajs.dom
import reactify.*

import scala.util.Try

class DealPanel(width: () => Double, initial: WorkspaceDeal, back: () => Unit) extends Container {
  layout @= Some(VerticalLayout(20.0))
  size.width := width()
  private var deal = initial
  private var tab = "Financials"
  private var dirty = false
  private val busy = Var(false)
  private val message = Var("")
  private val inner = () => width() - 48.0
  private val content = Column(18.0)()
  private val status = TextView.bound(message(), 13.0, Theme.danger())
  status.size.width := width()
  private val heading = WorkspaceStyle.text(deal.title, 28.0, bold = true)
  heading.size.width := width()
  heading.whiteSpace @= WhiteSpace.Normal
  heading.wrapAnywhere @= true
  heading.semantics @= Semantics.heading(1)
  private val state = WorkspaceStyle.paragraph("", width, 13.0)
  private val toolbar = Row(10.0)(WorkspaceStyle.button("Back to deals", () => if (canLeave) back()),
    WorkspaceStyle.button("Reload", () => if (canLeave) reload()))
  private val tabs = Row(8.0)()
  toolbar.layout := Some(if (width() < 280.0) VerticalLayout(8.0) else HorizontalLayout(10.0))
  tabs.visible := width() >= 700.0
  private val tabNames = List("Financials", "Judgments", "Policy", "Memo", "History")
  private val mobileTabs = new Select(tabNames)
  mobileTabs.size.width := width()
  mobileTabs.size.height @= 46.0
  mobileTabs.visible := width() < 700.0
  mobileTabs.node.el.querySelector("select").setAttribute("aria-label", "Review section")
  mobileTabs.selected.attach { index =>
    val selected = tabNames(index)
    if (selected != tab) {
      if (canLeave) { dirty = false; tab = selected; render() }
      else mobileTabs.selected @= tabNames.indexOf(tab)
    }
  }
  children := Vector(toolbar, heading, state) ++ (if (width() < 700.0) Vector(mobileTabs) else Vector(tabs)) ++ Vector(status, content)

  def processing: Boolean = busy()
  def canLeave: Boolean = !busy() && (!dirty || dom.window.confirm("Discard your unsaved changes?"))
  private def editable: Boolean = deal.stage != DealStage.Generating
  private def field(label: String, value: String = ""): TextInput = {
    val input = new TextInput(label)
    input.value @= value
    input.size.width := inner()
    input.readOnly := busy() || !editable
    input.value.attach(_ => dirty = true)
    input
  }
  private def select(label: String, values: List[String], index: Int = 0, track: Boolean = true): Select = {
    val input = new Select(values)
    input.selected @= index
    input.size.width := inner()
    input.size.height @= 46.0
    input.node.el.querySelector("select").setAttribute("aria-label", label)
    Val(busy() || !editable).attach(value => input.node.el.querySelector("select").asInstanceOf[dom.html.Select].disabled = value)
    if (track) input.selected.attach(_ => dirty = true)
    input
  }
  private def action(label: String)(run: => Unit): Button = {
    val button = WorkspaceStyle.button(label, () => run, primary = true)
    button.enabled := !busy() && editable
    button
  }
  private def save[A: RW](route: String, value: A): Unit = {
    busy @= true
    message @= ""
    ApiClient.action(deal.id, route, value) {
      case Right(updated) => busy @= false; dirty = false; deal = updated; render()
      case Left(error) => busy @= false; message @= error
    }
  }
  private def reload(): Unit = {
    busy @= true
    ApiClient.deal(deal.id) {
      case Right(updated) => busy @= false; dirty = false; deal = updated; render()
      case Left(error) => busy @= false; message @= error
    }
  }
  private def download(document: WorkspaceDocument): Unit = {
    val link = dom.document.createElement("a").asInstanceOf[dom.html.Anchor]
    link.href = s"/api/deals/${deal.id}/files/${document.id}"
    link.download = document.name
    link.click()
  }
  private def render(): Unit = {
    state.content @= s"${deal.stage} · Revision ${deal.revision} · ${deal.grid.values.size} cited values · ${deal.issues.indices.count(i => !deal.dispositions.contains(i.toString))} open review items"
    mobileTabs.selected @= tabNames.indexOf(tab)
    tabs.children @= tabNames.map { name =>
      val button = WorkspaceStyle.button(name, () => if (canLeave) { dirty = false; tab = name; render() }, primary = name == tab)
      button.enabled := !busy()
      button
    }.toVector
    content.children @= (tab match {
      case "Financials" => financials()
      case "Judgments" => judgments()
      case "Policy" => policy()
      case "Memo" => memo()
      case _ => history()
    })
    if (deal.stage == DealStage.Generating) dom.window.setTimeout(() => {
      if (node.el.isConnected && deal.stage == DealStage.Generating && !busy() && !dirty) reload()
    }, 3000)
  }

  private def financials(): Vector[Component] = {
    val detail = WorkspaceStyle.card(width, WorkspaceStyle.text("Inspect a value", 17.0, bold = true),
      WorkspaceStyle.paragraph("Select a financial line to see its citation or record a correction.", inner))
    def label(i: Int): String = deal.grid.values(i).item.toString.replaceAll("([a-z])([A-Z])", "$1 $2")
    val grid = new VirtualTable[Int](Var(deal.grid.values.indices.toVector), _.toString, label, Vector(
      GridColumn.text[Int]("item", "Financial line item", 280.0, sorts = true)(label),
      GridColumn.text[Int]("period", "Period", 180.0, sorts = true)(i => deal.grid.values(i).period.label),
      GridColumn.text[Int]("value", "USD millions", 160.0, align = TextAlign.End,
        sortBy = Some(Ordering.by[Int, BigDecimal](i => deal.grid.values(i).value)))(i => deal.grid.values(i).value.toString)), rowHeight = 44.0)
    grid.size.width := inner()
    grid.size.height @= math.min(440.0, deal.grid.values.size * 44.0 + 55.0)
    grid.fills @= true
    grid.selection.multiple @= false
    grid.selection.keys.attach { selected => selected.headOption.flatMap(_.toIntOption).foreach { index =>
      if (canLeave) {
        dirty = false
        val cell = deal.grid.values(index)
        val amount = field("Corrected amount (USD millions)", cell.value.toString)
        val reason = field("Reason for correction")
        detail.children @= Vector(WorkspaceStyle.text(s"${label(index)} · ${cell.period.label}", 17.0, bold = true),
          WorkspaceStyle.paragraph(cell.source.describe, inner, 13.0), amount, reason,
          action("Save correction") {
            Try(BigDecimal(amount.value())).toOption match {
              case None => message @= "Enter a valid amount."
              case Some(value) => save("corrections", DealCorrectionRequest(deal.revision,
                GridCorrection(cell.item, cell.period, cell.value, value, cell.source, reason.value(), "", "")))
            }
          })
      }
    }}
    val issues = deal.issues.zipWithIndex.map { (issue, index) =>
      val reason = field("Review explanation", deal.dispositions.getOrElse(index.toString, ""))
      WorkspaceStyle.card(width, WorkspaceStyle.text(s"${if (deal.dispositions.contains(index.toString)) "Reviewed" else "Needs review"}: ${issue.kind}", 16.0, bold = true),
        WorkspaceStyle.paragraph(s"${issue.location}\n${issue.message}", inner), reason,
        action("Record issue review") { save("issues", IssueResolutionRequest(deal.revision, index, reason.value())) })
    }
    val sources = deal.documents.filterNot(_.name.startsWith("generated-")).map { doc =>
      WorkspaceStyle.button(s"Download ${doc.name}", () => download(doc))
    }
    Vector(WorkspaceStyle.card(width, WorkspaceStyle.text("Golden Grid", 20.0, bold = true), grid), detail,
      WorkspaceStyle.card(width, (Vector(WorkspaceStyle.text("Source documents", 18.0, bold = true)) ++ sources)*)) ++ issues
  }

  private def judgments(): Vector[Component] = deal.record match {
    case None => Vector(new CreditSetupPanel(width, deal, () => busy(), () => dirty = true, () => canLeave,
      request => save("profile", request), request => save("documents", request), error => message @= error))
    case Some(record) =>
      val analysis = CreditAnalysis.run(record)
      val covenantLabel = record.profile.covenant.kind match {
        case CovenantKind.MaxNetLeverage => "Maximum net leverage"
        case CovenantKind.MaxDebtToCapitalization => "Maximum debt to capitalization"
      }
      def covenantValue(value: BigDecimal): String = record.profile.covenant.kind match {
        case CovenantKind.MaxNetLeverage => s"${value.setScale(2, BigDecimal.RoundingMode.HALF_UP)}x"
        case CovenantKind.MaxDebtToCapitalization => s"${(value * 100).setScale(2, BigDecimal.RoundingMode.HALF_UP)}%"
      }
      val support = select("Ownership support", Support.values.toList.map(_.label), Support.values.indexOf(record.inputs.support))
      val basis = field("Ownership support rationale", record.inputs.supportBasis)
      val concentration = field("Largest concentration (%)", (record.profile.largestShare.value * 100).toString)
      val linked = field("Commodity-price-linked share (%)", (record.profile.priceLinkedShare.value * 100).toString)
      val customer = select("Customer above 10%", List("No customer above 10%", "Customer above 10%"), if (record.inputs.customerOverTenPercent) 1 else 0)
      val reason = field("Reason for judgment changes")
      val ratingReview = select("Rating override review", List("Keep existing review status", "Existing override reviewed and confirmed"))
      val shock = field("Additional EBITDA reduction (%)")
      val recommendation = select("Credit recommendation", CreditRecommendation.values.toList.map(_.label), CreditRecommendation.values.indexOf(deal.recommendation))
      val rationale = field("Recommendation rationale", deal.recommendationBasis)
      Vector(WorkspaceStyle.card(width, WorkspaceStyle.text("Credit analysis", 20.0, bold = true),
        WorkspaceStyle.paragraph(s"Final risk rating: ${analysis.rating.grade.label} (Calculated: ${analysis.rating.machineGrade.label})\nNet leverage: ${analysis.ratios.netLeverage.setScale(2, BigDecimal.RoundingMode.HALF_UP)}x\nEBITDA: ${analysis.ratios.ebitda} million\nActual covenant: $covenantLabel\nCovenant result: ${covenantValue(analysis.covenant.actual)} against ${covenantValue(analysis.covenant.threshold)}", inner, muted = false)),
        WorkspaceStyle.card(width, WorkspaceStyle.text("Analyst judgments", 20.0, bold = true),
          WorkspaceStyle.paragraph("Changes recalculate the analysis and require a new approval.", inner), support, basis, concentration, linked, customer, ratingReview, reason,
          action("Save judgments") {
            val selected = Support.values(support.selected())
            Try((BigDecimal(concentration.value()) / 100, BigDecimal(linked.value()) / 100)).toOption match {
              case None => message @= "Enter valid concentration percentages."
              case Some((largest, priceLinked)) =>
                val above = customer.selected() == 1
                save("judgments", JudgmentRequest(deal.revision, record.inputs.copy(support = selected, supportBasis = basis.value(),
                  customerOverTenPercent = above, ratingReview = if (ratingReview.selected() == 1) None else record.inputs.ratingReview),
                  record.profile.copy(support = selected, customerOverTenPercent = above,
                    largestShare = Sourced(largest, record.profile.largestShare.source), priceLinkedShare = Sourced(priceLinked, record.profile.priceLinkedShare.source)), reason.value(), overrideReviewed = ratingReview.selected() == 1))
            }
          }), WorkspaceStyle.card(width, WorkspaceStyle.text("Sensitivity scenarios", 20.0, bold = true),
            WorkspaceStyle.paragraph("Standard EBITDA reductions of 20%, 30% and 40% are included. Add an institution-specific reduction below. Tests use the facility's actual covenant.", inner),
            shock, action("Add sensitivity") {
              Try(BigDecimal(shock.value())).toOption.filter(v => v > 0 && v <= 100) match {
                case None => message @= "Enter a reduction greater than 0% and no more than 100%."
                case Some(value) =>
                  val scenarios = record.inputs.stressScenarios :+ StressScenario(s"EBITDA -$value%", ebitdaChange = -value / 100)
                  save("judgments", JudgmentRequest(deal.revision, record.inputs.copy(stressScenarios = scenarios), record.profile.copy(stressScenarios = scenarios), s"Added EBITDA sensitivity of $value%"))
              }
            }), WorkspaceStyle.card(width, WorkspaceStyle.text("Credit recommendation", 20.0, bold = true),
              WorkspaceStyle.paragraph("The analyst records the credit recommendation separately from approving the financial inputs.", inner),
              recommendation, rationale, action("Save recommendation") {
                save("recommendation", RecommendationRequest(deal.revision, CreditRecommendation.values(recommendation.selected()), rationale.value()))
              }))
  }

  private def policy(): Vector[Component] = {
    val name = field("Policy name", deal.policy.fold("")(_.name))
    val version = field("Policy version", deal.policy.fold("")(_.version))
    val source = field("Policy source or reference", deal.policy.fold("")(_.source))
    val description = field("Rule description")
    val metric = select("Policy metric", PolicyMetric.values.toList.map(_.toString))
    val operator = select("Rule comparison", List("At most", "At least"))
    val threshold = field("Threshold")
    val reason = field("Reason for policy change")
    val existing = deal.policy.toList.flatMap(_.rules)
    val chooseRule = select("Rule to edit", "New rule" :: existing.map(_.description), track = false)
    chooseRule.selected.attach { index =>
      val rule = existing.lift(index - 1)
      description.value @= rule.fold("")(_.description)
      metric.selected @= rule.fold(0)(r => PolicyMetric.values.indexOf(r.metric))
      operator.selected @= rule.fold(0)(r => PolicyOperator.values.indexOf(r.operator))
      threshold.value @= rule.fold("")(_.threshold.toString)
    }
    val clear = action("Remove institution policy") { save("policy-clear", DealActionRequest(deal.revision, reason.value())) }
    clear.visible @= deal.policy.nonEmpty
    val rules = existing.map { rule =>
      val result = deal.policyResults.find(_.rule == rule.id)
      WorkspaceStyle.paragraph(s"${rule.description}: ${rule.metric} ${rule.operator} ${rule.threshold} · ${result.fold("Pending")(_.status.toString)}", inner)
    }
    Vector(WorkspaceStyle.card(width, (Vector(WorkspaceStyle.text("Institution policy", 20.0, bold = true),
      WorkspaceStyle.paragraph("Rules are evaluated separately from the risk rating. Enter the institution's documented policy; no policy is assumed by default.", inner)) ++ rules)*),
      WorkspaceStyle.card(width, WorkspaceStyle.text("Edit policy rules", 18.0, bold = true), name, version, source, chooseRule, description, metric, operator, threshold, reason,
        action("Save policy rule") {
          Try(BigDecimal(threshold.value())).toOption match {
            case None => message @= "Enter a numeric policy threshold."
            case Some(value) =>
              val selected = existing.lift(chooseRule.selected() - 1)
              val rule = PolicyRule(selected.fold(s"rule-${deal.revision}")(_.id), description.value(), PolicyMetric.values(metric.selected()), PolicyOperator.values(operator.selected()), value)
              val updated = if (selected.isEmpty) existing :+ rule else existing.map(r => if (r.id == rule.id) rule else r)
              save("policy", PolicyRequest(deal.revision, PolicySet(name.value(), version.value(), source.value(), updated), reason.value()))
          }
        }, clear))
  }

  private def memo(): Vector[Component] = {
    val reason = field("Approval explanation or policy exception")
    val approve = action("Approve reviewed inputs") { save("approve", DealActionRequest(deal.revision, reason.value())) }
    approve.enabled := !busy() && (deal.stage == DealStage.Review || deal.stage == DealStage.Failed)
    val generate = WorkspaceStyle.button("Generate memo", () => save("resume", DealActionRequest(deal.revision)), primary = true)
    generate.enabled := !busy() && (deal.stage == DealStage.Approved || deal.stage == DealStage.Failed ||
      (deal.stage == DealStage.Generating && deal.lease.exists(_.expiresAt < System.currentTimeMillis())))
    val files = deal.documents.filter(_.name.startsWith("generated-")).reverse.map { document =>
      val label = document.contentType match {
        case "application/pdf" => "memo PDF"
        case "application/zip" => "review bundle"
        case _ if document.name.contains("audit") => "source audit"
        case _ => "memo HTML"
      }
      val revision = """-r([0-9]+)\.""".r.findFirstMatchIn(document.name).fold("")(m => s" · Revision ${m.group(1)}")
      WorkspaceStyle.button(s"Download $label$revision", () => download(document))
    }
    val approval = if (deal.stage == DealStage.Completed) WorkspaceStyle.card(width,
      WorkspaceStyle.text("Memo ready", 20.0, bold = true),
      WorkspaceStyle.paragraph(deal.approval.fold("")(a => s"Reviewed by ${a.actor}. The memo and supporting evidence are available below. Any further edit requires a new approval."), inner))
    else if (deal.stage == DealStage.Generating) WorkspaceStyle.card(width,
      WorkspaceStyle.text("Preparing your memo", 20.0, bold = true),
      WorkspaceStyle.paragraph("Your approved inputs are saved. This page updates automatically; you may return to the deal later. If processing is interrupted, resume becomes available when the current job expires.", inner), generate)
    else WorkspaceStyle.card(width, WorkspaceStyle.text("Review and approve", 20.0, bold = true),
      WorkspaceStyle.paragraph(deal.failure.getOrElse("Resolve extraction issues and review your judgments before approval. Any policy exception must be explained. Editing approved inputs returns the deal to review."), inner),
      reason, approve, generate)
    val narrative = deal.record.toVector.map { _ =>
      val sections = MemoSections.titles
      val section = select("Memo section", sections, track = false)
      val editor = new Container
      editor.size.width := inner()
      editor.size.height @= 240.0
      val area = dom.document.createElement("textarea").asInstanceOf[dom.html.TextArea]
      area.setAttribute("aria-label", "Section narrative")
      area.style.cssText = "width:100%;height:100%;padding:14px;border:1px solid #dde4eb;border-radius:8px;font:14px/1.6 sans-serif;resize:none;box-sizing:border-box"
      area.value = deal.narratives.getOrElse(sections.head, "")
      area.disabled = !editable
      editor.node.el.appendChild(area)
      val riskOrder = new Container
      riskOrder.size.width := inner()
      val list = dom.document.createElement("div").asInstanceOf[dom.html.Div]
      list.style.cssText = "font:13px/1.5 sans-serif;color:#203344"
      riskOrder.node.el.appendChild(list)
      var dragged = -1
      def showRisks(): Unit = {
        val blocks = if (sections(section.selected()) == "Key Risk Factors and Mitigants") area.value.split("\\n\\s*\\n").toVector.filter(_.trim.nonEmpty) else Vector.empty
        list.textContent = ""
        riskOrder.size.height @= blocks.size * 78.0
        blocks.zipWithIndex.foreach { (text, index) =>
          val row = dom.document.createElement("div").asInstanceOf[dom.html.Div]
          row.draggable = editable
          row.style.cssText = "height:68px;margin:5px 0;padding:10px;background:#f5f7fa;border:1px solid #dde4eb;border-radius:6px;box-sizing:border-box;overflow:hidden"
          val label = dom.document.createElement("span")
          label.textContent = s"${index + 1}. ${text.take(90)} "
          row.appendChild(label)
          def move(target: Int): Unit = if (target >= 0 && target < blocks.size && editable) {
            val reordered = blocks.patch(index, Nil, 1).patch(target, Vector(blocks(index)), 0)
            area.value = reordered.mkString("\n\n")
            dirty = true
            showRisks()
          }
          List("Move up" -> (index - 1), "Move down" -> (index + 1)).foreach { (name, target) =>
            val button = dom.document.createElement("button").asInstanceOf[dom.html.Button]
            button.textContent = name
            button.setAttribute("aria-label", s"$name risk ${index + 1}")
            button.disabled = !editable || target < 0 || target >= blocks.size
            button.style.cssText = "margin-left:8px;background:white;border:1px solid #dde4eb;border-radius:4px;padding:4px;color:#087f79"
            button.onclick = _ => move(target)
            row.appendChild(button)
          }
          row.ondragstart = (_: dom.DragEvent) => dragged = index
          row.ondragover = (event: dom.DragEvent) => event.preventDefault()
          row.ondrop = (event: dom.DragEvent) => {
            event.preventDefault()
            if (dragged >= 0 && dragged < blocks.size && dragged != index && editable) {
              area.value = blocks.patch(dragged, Nil, 1).patch(index, Vector(blocks(dragged)), 0).mkString("\n\n")
              dragged = -1
              dirty = true
              showRisks()
            }
          }
          list.appendChild(row)
        }
      }
      area.oninput = _ => { dirty = true; showRisks() }
      var selectedSection = 0
      section.selected.attach { index =>
        if (index != selectedSection) {
          if (!dirty || dom.window.confirm("Discard unsaved narrative text?")) {
            selectedSection = index
            area.value = deal.narratives.getOrElse(sections(index), "")
            dirty = false
            showRisks()
          } else section.selected @= selectedSection
        }
      }
      showRisks()
      val explanation = field("Reason for narrative change")
      WorkspaceStyle.card(width, WorkspaceStyle.text("Analyst narrative", 20.0, bold = true),
        WorkspaceStyle.paragraph("Write or revise the selected memo section. Saved prose is included in the next approved memo.", inner), section, editor, riskOrder, explanation,
        action("Save narrative") { save("narratives", NarrativeRequest(deal.revision, sections(section.selected()), area.value, explanation.value())) })
    }
    Vector(approval) ++ narrative ++ Vector(WorkspaceStyle.card(width, (Vector(WorkspaceStyle.text("Memo downloads", 18.0, bold = true)) ++ files)*))
  }

  private def history(): Vector[Component] = deal.events.reverse.map { event =>
    val detail = Try {
      event.action match {
        case "Credit profile added" => JsonParser(event.detail).as[CreditSetupRequest].reason
        case "Grid corrected" =>
          val correction = JsonParser(event.detail).as[GridCorrection]
          s"${correction.item}, ${correction.period.label}: ${correction.expected} to ${correction.value} million. ${correction.reason}"
        case "Analyst judgments changed" => JsonParser(event.detail).as[JudgmentRequest].reason
        case "Policy changed" =>
          val request = JsonParser(event.detail).as[PolicyRequest]
          s"${request.policy.name}, version ${request.policy.version}: ${request.reason}"
        case "Policy removed" => event.detail.split("; prior policy:", 2).head
        case "Narrative edited" =>
          val request = JsonParser(event.detail).as[NarrativeRequest]
          s"${request.section}: ${request.reason}"
        case "Credit recommendation changed" =>
          val request = JsonParser(event.detail).as[RecommendationRequest]
          s"${request.recommendation.label}: ${request.rationale}"
        case _ => event.detail
      }
    }.getOrElse(event.detail.take(1000))
    WorkspaceStyle.card(width, WorkspaceStyle.text(event.action, 17.0, bold = true),
      WorkspaceStyle.paragraph(s"${event.actor} · ${event.at} · Revision ${event.revision}", inner, 12.0),
      WorkspaceStyle.paragraph(detail, inner, 13.0))
  }.toVector
  render()
}
