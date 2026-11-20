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

class DealPanel(width: () => Double, initial: WorkspaceDeal, back: () => Unit, initialSection: String = "Financials") extends Container {
  layout @= Some(VerticalLayout(20.0))
  size.width := width()
  private var deal = initial
  private var tab = initialSection
  private var dirty = false
  private var memoScroll = 0.0
  private var reviewPanel = Option.empty[MemoReviewPanel]
  private val busy = Var(false)
  private val message = Var("")
  private val saved = Var(false)
  private val inner = () => width() - 48.0
  private val content = Column(18.0)()
  private val status = TextView.bound(message(), 13.0, Theme.danger())
  status.size.width := width()
  status.color := (if (saved()) WorkspaceStyle.Teal else Theme.danger())
  status.node.el.setAttribute("role", "status")
  status.node.el.setAttribute("tabindex", "-1")
  message.attach { value => if (value.nonEmpty) dom.window.setTimeout(() => {
    status.node.el.scrollIntoView()
    status.node.el.asInstanceOf[dom.html.Element].focus()
  }, 50) }
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
  private val tabNames = List("Overview", "Financials", "Judgments", "Memo", "History")
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
  children := Vector(toolbar, heading, state) ++ Vector(mobileTabs, tabs) ++ Vector(status, content)

  def processing: Boolean = busy() || reviewPanel.exists(_.processing)
  def canLeave: Boolean = !processing && reviewPanel.forall(_.canLeave) && (!dirty || dom.window.confirm("Discard your unsaved changes?"))
  private def editable: Boolean = !Set(DealStage.Generating, DealStage.Importing, DealStage.ImportFailed).contains(deal.stage)
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
    saved @= false
    message @= ""
    ApiClient.action(deal.id, route, value) {
      case Right(updated) => busy @= false; dirty = false; deal = updated; saved @= true; message @= "Changes saved."; render()
      case Left(error) => busy @= false; message @= error
    }
  }
  private def reload(): Unit = {
    saved @= false
    message @= ""
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
    children @= (if (deal.stage == DealStage.Generating) Vector(toolbar, status, content) else Vector(toolbar, heading, state) ++ Vector(mobileTabs, tabs) ++ Vector(status, content))
    heading.content @= deal.title
    state.content @= s"${WorkspaceStyle.stageLabel(deal.stage)} · Revision ${deal.revision} · ${deal.grid.values.size} cited values · ${deal.issues.indices.count(i => !deal.dispositions.contains(i.toString))} open review items"
    mobileTabs.selected @= tabNames.indexOf(tab)
    tabs.children @= tabNames.map { name =>
      val button = WorkspaceStyle.button(name, () => if (canLeave) { dirty = false; tab = name; render() }, primary = name == tab)
      button.enabled := !busy()
      button
    }.toVector
    reviewPanel = None
    content.children @= (if (Set(DealStage.Importing, DealStage.ImportFailed).contains(deal.stage)) importProgress() else tab match {
      case "Overview" => overview()
      case "Financials" => financials()
      case "Judgments" => judgments()
      case "Memo" => memo()
      case _ => history()
    })
    if (deal.stage == DealStage.Generating || deal.stage == DealStage.Importing) dom.window.setTimeout(() => {
      if (node.el.isConnected && Set(DealStage.Generating, DealStage.Importing).contains(deal.stage) && !busy() && !dirty) reload()
    }, 3000)
  }

  private def importProgress(): Vector[Component] = {
    val retry = WorkspaceStyle.button("Retry SEC import", () => save("public-retry", DealActionRequest(deal.revision, "Retry SEC retrieval")))
    retry.enabled := !busy() && (deal.stage == DealStage.ImportFailed || deal.lease.exists(_.expiresAt < System.currentTimeMillis()))
    Vector(WorkspaceStyle.card(width,
      WorkspaceStyle.text(WorkspaceStyle.stageLabel(deal.stage), 22.0, bold = true),
      WorkspaceStyle.paragraph(deal.failure.getOrElse("Retrieving SEC filings and reported financial facts. This import is saved; you can return from the portfolio. No financial values or credit terms are invented."), inner), retry))
  }

  private def overview(): Vector[Component] = {
    val unresolved = deal.issues.indices.count(i => !deal.dispositions.contains(i.toString))
    def step(title: String, detail: String, destination: String, label: String): Component = {
      WorkspaceStyle.card(width, WorkspaceStyle.text(title, 18.0, bold = true),
        WorkspaceStyle.paragraph(detail, inner), WorkspaceStyle.button(label, () => {
          if (canLeave) { dirty = false; tab = destination; render() }
        }))
    }
    Vector(
      WorkspaceStyle.card(width, WorkspaceStyle.text("Your review at a glance", 20.0, bold = true),
        WorkspaceStyle.paragraph("Work through the financials, document your judgment, then approve the inputs for your memo. You can revisit any section before approval.", inner)),
      step("1. Financials and sources", s"${deal.grid.values.size} cited values · $unresolved extraction issues awaiting review. Inspect source evidence and record corrections here.", "Financials", "Review financials"),
      step("2. Credit judgment", if (deal.record.isEmpty) "Complete the credit profile before generating a full credit memo." else "Review the credit profile, risk rating, recommendation and sensitivity assumptions.", "Judgments", "Review judgments"),
      step("3. Approval and memo", "Review any remaining blockers, approve the financial inputs, then generate your draft. Bank-side screening is handled outside this underwriting workflow.", "Memo", "Review and prepare memo"),
      step("Review history", "See saved changes, evidence and approvals for this deal.", "History", "View history"))
  }

  private def financials(): Vector[Component] = {
    val borrower = field("Borrower legal name", deal.title)
    val identityReason = field("Reason for borrower name correction")
    val identity = WorkspaceStyle.card(width, WorkspaceStyle.text("Borrower details", 20.0, bold = true), borrower, identityReason,
      action("Save borrower name") { save("identity", BorrowerRequest(deal.revision, borrower.value(), identityReason.value())) })
    val detail = WorkspaceStyle.card(width, WorkspaceStyle.text("Inspect a value", 17.0, bold = true),
      WorkspaceStyle.paragraph("Select a financial line to see its citation or record a correction.", inner))
    def label(i: Int): String = deal.grid.values(i).item.toString.replaceAll("([a-z])([A-Z])", "$1 $2")
    val visibleRows = Var(Vector.empty[Int])
    val periods = deal.grid.values.map(_.period).distinct.sortBy(p => (-p.end.toEpochDay, p.label))
    val periodFilter = select("Financial period", "All periods" :: periods.map(_.label), track = false)
    var pageIndex = 0
    val pageSize = 15
    var matches = Vector.empty[Int]
    val search = new TextInput("Find a financial line or period")
    search.size.width := inner()
    val resultCount = WorkspaceStyle.text("", 13.0, muted = true)
    lazy val previous = WorkspaceStyle.button("Previous values", () => { pageIndex -= 1; showPage() })
    lazy val next = WorkspaceStyle.button("Next values", () => { pageIndex += 1; showPage() })
    def showPage(): Unit = {
      pageIndex = math.max(0, math.min(pageIndex, math.max(0, (matches.size - 1) / pageSize)))
      visibleRows @= matches.slice(pageIndex * pageSize, (pageIndex + 1) * pageSize)
      previous.enabled @= pageIndex > 0
      next.enabled @= (pageIndex + 1) * pageSize < matches.size
      resultCount.content @= (if (matches.isEmpty) "No matching values" else s"Showing ${pageIndex * pageSize + 1}–${pageIndex * pageSize + visibleRows().size} of ${matches.size} matching values") + s" · ${deal.grid.values.size} total cited values"
    }
    def filterRows(): Unit = {
      val query = search.value().trim.toLowerCase
      matches = deal.grid.values.indices.filter { i =>
        val period = deal.grid.values(i).period
        s"${label(i)} ${period.label}".toLowerCase.contains(query) &&
          (periodFilter.selected() == 0 || periods.lift(periodFilter.selected() - 1).contains(period))
      }.toVector.sortBy(i => (-deal.grid.values(i).period.end.toEpochDay, deal.grid.values(i).period.label, label(i)))
      pageIndex = 0
      showPage()
    }
    search.value.attach(_ => filterRows())
    periodFilter.selected.attach(_ => filterRows())
    filterRows()
    val grid = new VirtualTable[Int](visibleRows, _.toString, label, Vector(
      GridColumn.text[Int]("item", "Financial line item", 280.0, sorts = true)(label),
      GridColumn.text[Int]("period", "Period", 180.0, sorts = true)(i => deal.grid.values(i).period.label),
      GridColumn.text[Int]("value", "USD millions", 160.0, align = TextAlign.End,
        sortBy = Some(Ordering.by[Int, BigDecimal](i => deal.grid.values(i).value)))(i => deal.grid.values(i).value.toString)), rowHeight = 44.0)
    grid.size.width := inner()
    grid.size.height := visibleRows().size * 44.0 + 65.0
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
    Vector(identity, WorkspaceStyle.card(width, WorkspaceStyle.text("Financials by period", 20.0, bold = true),
      WorkspaceStyle.paragraph("Latest periods appear first. Choose a period or use Previous / Next values to review the entire grid. Select a value for its source and corrections.", inner),
      periodFilter, search, resultCount, grid, Row(12.0)(previous, next)), detail,
      WorkspaceStyle.button("Continue to credit review", () => if (canLeave) { tab = "Judgments"; render() }, primary = true),
      WorkspaceStyle.card(width, (Vector(WorkspaceStyle.text("Source documents", 18.0, bold = true)) ++ sources)*),
      new MissingFinancialPanel(width, deal, () => busy(), r => save("financial-values", r), error => message @= error)) ++ issues
  }

  private def judgments(): Vector[Component] = deal.record match {
    case None => Vector(new CreditSetupPanel(width, deal, () => busy(), () => dirty = true, () => canLeave,
      request => save("profile", request), request => save("documents", request), error => message @= error,
      request => save("profile-draft", request)))
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
            }))

  }

  private def requiredChecks(): Component = {
    val rows = deal.stopReviews.map(_.topic).distinct.toVector.map { topic =>
      val previous = deal.stopReviews.find(_.topic == topic)
      val state = select(s"${topic.label} status", List("Pending review", "Clear", "Stop"), previous.fold(0)(_.status.ordinal))
      val evidence = field(s"${topic.label} evidence", previous.fold("")(_.evidence))
      (topic, state, evidence)
    }
    WorkspaceStyle.card(width, (Vector(
      WorkspaceStyle.text("Previously recorded review issues", 20.0, bold = true),
      WorkspaceStyle.paragraph("These entries were retained from earlier reviews. An explicit Stop must be resolved with evidence before approval; routine bank-side screening is not repeated here.", inner)) ++
      rows.flatMap { (topic, state, evidence) => Vector(WorkspaceStyle.text(topic.label, 14.0, bold = true), state, evidence) } ++
      Vector(action("Save review issues") {
        save("checks", StopReviewRequest(deal.revision, rows.map { (topic, state, evidence) =>
          StopReview(topic, ReviewStatus.values(state.selected()), evidence.value())
        }.toList))
      }))* )
  }

  private def memo(): Vector[Component] = {
    if (deal.stage == DealStage.Generating) {
      val resume = WorkspaceStyle.button("Resume interrupted generation", () => save("resume", DealActionRequest(deal.revision)))
      resume.enabled := !busy() && deal.lease.exists(_.expiresAt < System.currentTimeMillis())
      return Vector(new ProcessingPanel(width, if (deal.submission.nonEmpty) "Preparing committee submission" else "Preparing your memo", Some(deal.progress)), resume)
    }
    val reason = field("Approval explanation or policy exception")
    val approve = action("Approve reviewed inputs") { save("approve", DealActionRequest(deal.revision, reason.value())) }
    approve.enabled := !busy() && (deal.stage == DealStage.Review || deal.stage == DealStage.Failed)
    val generate = WorkspaceStyle.button("Generate memo", () => save("resume", DealActionRequest(deal.revision)), primary = true)
    val policyChanged = Var(false)
    generate.enabled := !busy() && !policyChanged() && (deal.stage == DealStage.Approved || deal.stage == DealStage.Failed)
    val ready = deal.stage == DealStage.Completed || deal.stage == DealStage.Submitted
    val policySummary = Column(10.0)(WorkspaceStyle.paragraph("Loading workspace policy...", inner))
    ApiClient.workspacePolicy {
      case Left(error) => policySummary.children @= Vector(WorkspaceStyle.paragraph(error, inner))
      case Right(settings) =>
        val active = if (settings.revision == 0) deal.policy else settings.policy
        val basis = if (active.nonEmpty) PolicyBasis.Institution else PolicyBasis.GoldenSource
        val results = PolicyEngine.evaluate(active, deal.record, basis)
        policyChanged @= deal.approval.nonEmpty && deal.workspacePolicyRevision.getOrElse(0L) != settings.revision
        val reopen = WorkspaceStyle.button("Reopen input review", () => save("reopen", DealActionRequest(deal.revision, "Review the updated workspace policy")))
        policySummary.children @= Vector(WorkspaceStyle.paragraph(active.fold("Golden Source guidance applies. No institution overlay is configured.")(p => s"Workspace policy: ${p.name}, version ${p.version}"), inner)) ++
          results.filterNot(_.status == PolicyStatus.NotApplicable).map(r => WorkspaceStyle.paragraph(s"${r.description}: ${r.status.label}", inner)).toVector ++
          (if (policyChanged()) Vector(WorkspaceStyle.paragraph("The workspace policy changed after input approval. Review it and approve the inputs again before generation or committee submission.", inner)) ++ (if (ready) Vector(reopen) else Vector.empty) else Vector.empty)
    }
    val blockers = Vector(
      Option.when(deal.publicIssuer.nonEmpty && deal.record.isEmpty)("Complete and save the credit profile in Judgments."),
      Option.when(deal.issues.indices.exists(i => !deal.dispositions.contains(i.toString)))("Resolve the extraction review items in Financials."),
      deal.publicIssuer.flatMap(_.methodologyIssue),
      Option.when(deal.record.exists(_.inputs.ratingReview.nonEmpty))("Review the existing risk-rating override in Judgments."),
      Option.when(deal.stopReviews.exists(_.status == ReviewStatus.Stop))("Resolve the recorded stop condition before approval.")
    ).flatten
    approve.enabled := !busy() && blockers.isEmpty && (deal.stage == DealStage.Review || deal.stage == DealStage.Failed || deal.stage == DealStage.Approved)
    val prepare = if (ready) Vector.empty else Vector(WorkspaceStyle.card(width,
      WorkspaceStyle.text("Prepare the memo", 20.0, bold = true),
      WorkspaceStyle.paragraph(deal.failure.getOrElse("Review your financials and credit profile, approve the inputs, then generate the draft. Your workspace policy applies automatically. Bank-side screening is outside this workflow; existing recorded stops still require resolution."), inner),
      WorkspaceStyle.paragraph(if (blockers.isEmpty) "Inputs are ready for analyst approval." else "Before approval:\n" + blockers.mkString("\n"), inner), reason, approve, generate))
    val document = if (deal.record.nonEmpty) {
      val panel = new MemoReviewPanel(width, deal, updated => {
        memoScroll = reviewPanel.fold(0.0)(_.scrollPosition)
        deal = updated; dirty = false; saved @= true; message @= "Changes saved."; render()
      }, memoScroll, value => busy @= value)
      reviewPanel = Some(panel)
      Vector(panel)
    } else Vector(WorkspaceStyle.card(width, WorkspaceStyle.text("Complete the credit profile", 20.0, bold = true),
      WorkspaceStyle.paragraph("Financial statements alone do not establish facility terms or credit judgments. Complete the sourced profile before preparing a committee memo.", inner),
      WorkspaceStyle.button("Complete credit profile", () => { tab = "Judgments"; render() })))
    val latest = deal.documents.filter(d => d.name.startsWith("generated-") && d.contentType != "application/json").reverse.groupBy(d => if (d.name.contains("audit")) "audit" else d.contentType).values.map(_.head).toVector
    val downloads = if (!ready) Vector.empty else Vector(WorkspaceStyle.card(width, (Vector(WorkspaceStyle.text("Memo and evidence", 18.0, bold = true)) ++ latest.map { document =>
      val label = document.contentType match {
        case "application/pdf" => "memo PDF"
        case "application/zip" => "review bundle"
        case _ if document.name.contains("audit") => "source audit"
        case _ => "memo HTML"
      }
      WorkspaceStyle.button(s"Download $label", () => download(document))
    })* ))
    Vector(WorkspaceStyle.card(width, WorkspaceStyle.text("Applicable policy", 18.0, bold = true), policySummary)) ++
      (if (deal.stopReviews.nonEmpty && !ready) Vector(requiredChecks()) else Vector.empty) ++ prepare ++ document ++ downloads
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
        case "Required checks reviewed" => JsonParser(event.detail).as[List[StopReview]]
          .map(r => s"${r.topic.label}: ${r.status}. ${r.evidence}").mkString("\n\n")
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
