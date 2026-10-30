package logicov.ui

import everyui.component.{Column, Container, Row, Select, TextInput}
import everyui.layout.{HorizontalLayout, VerticalLayout}
import logicov.workspace.{DealStage, DealSummary, WorkspaceDeal}
import org.scalajs.dom
import reactify.*

class WorkspacePanel(width: () => Double) extends Container {
  layout @= Some(VerticalLayout(20.0))
  size.width := width()
  private var importer: Option[ImportPanel] = None
  private var detail: Option[DealPanel] = None
  private var generation = 0
  private var searchTerm = ""
  private var stageIndex = 0
  private var sortIndex = 0
  def processing: Boolean = importer.exists(_.processing) || detail.exists(_.processing)
  def canLeave: Boolean = importer.forall(_.canLeave) && detail.forall(_.canLeave)

  private def portfolio(): Unit = {
    generation += 1
    importer = None
    detail = None
    children @= Vector(WorkspaceStyle.text("Loading your workspace...", 18.0))
    val current = generation
    ApiClient.deals {
      case Left(error) if current == generation => children @= Vector(WorkspaceStyle.paragraph(error, width), WorkspaceStyle.button("Try again", () => portfolio()))
      case Right(deals) if current == generation => showPortfolio(deals)
      case _ => ()
    }
  }

  private def showPortfolio(deals: List[DealSummary]): Unit = {
    val search = new TextInput("Search borrowers")
    search.value @= searchTerm
    search.size.width := width()
    val stages = new Select(List("All deals", "Needs review", "Ready to generate", "Preparing memo", "Memo ready", "Needs attention"))
    stages.selected @= stageIndex
    stages.size.width := (if (width() >= 700.0) (width() - 16.0) / 2.0 else width())
    stages.size.height @= 46.0
    stages.node.el.querySelector("select").setAttribute("aria-label", "Deal status")
    val sort = new Select(List("Recently updated", "Borrower A to Z", "Needs attention first"))
    sort.selected @= sortIndex
    sort.size.width := (if (width() >= 700.0) (width() - 16.0) / 2.0 else width())
    sort.size.height @= 46.0
    sort.node.el.querySelector("select").setAttribute("aria-label", "Sort deals")
    val filters = Row(16.0)(
      Column(8.0)(WorkspaceStyle.text("Status", 13.0, muted = true), stages),
      Column(8.0)(WorkspaceStyle.text("Sort by", 13.0, muted = true), sort))
    filters.layout := Some(if (width() >= 700.0) HorizontalLayout(16.0) else VerticalLayout(12.0))
    val count = WorkspaceStyle.text("", 13.0, muted = true)
    val rows = Column(12.0)()
    def filter(): Unit = {
      val selected = List(None, Some(DealStage.Review), Some(DealStage.Approved), Some(DealStage.Generating), Some(DealStage.Completed), Some(DealStage.Failed))(stages.selected())
      searchTerm = search.value()
      stageIndex = stages.selected()
      sortIndex = sort.selected()
      val matching = deals.filter(d => d.title.toLowerCase.contains(searchTerm.trim.toLowerCase) && selected.forall(_ == d.stage))
      val found = sortIndex match {
        case 1 => matching.sortBy(_.title.toLowerCase)
        case 2 => matching.sortBy(d => (if (d.stage == DealStage.Failed) 0 else if (d.stage == DealStage.Review) 1 else 2, d.title.toLowerCase))
        case _ => matching.sortBy(_.updatedAt).reverse
      }
      count.content @= s"Showing ${found.size} of ${deals.size} deals"
      rows.children @= (if (found.isEmpty) Vector(WorkspaceStyle.card(width,
        WorkspaceStyle.text(if (deals.isEmpty) "Your first deal starts here" else "No matching deals", 20.0, bold = true),
        WorkspaceStyle.paragraph(if (deals.isEmpty) "Import financial statements to start a cited financial review." else "Try another borrower name or clear your filters to see all deals.", () => width() - 48.0),
        WorkspaceStyle.button("Clear filters", () => { search.value @= ""; stages.selected @= 0 })))
      else found.map { deal => WorkspaceStyle.card(width,
        WorkspaceStyle.paragraph(deal.title, () => width() - 48.0, 20.0, muted = false),
        WorkspaceStyle.paragraph(s"${WorkspaceStyle.stageLabel(deal.stage)} · ${deal.values} cited values · ${deal.issues} open review items · Revision ${deal.revision}", () => width() - 48.0, 13.0),
        WorkspaceStyle.button("Open deal", () => open(deal.id))) }.toVector)
    }
    search.value.attach(_ => filter())
    stages.selected.attach(_ => filter())
    sort.selected.attach(_ => filter())
    filter()
    children @= Vector(WorkspaceStyle.text("Deal workspace", 30.0, bold = true),
      WorkspaceStyle.paragraph("Financial analysis, review and approval in one place.", width, 15.0),
      WorkspaceStyle.card(width, WorkspaceStyle.text(s"${deals.size} deals  ·  ${deals.count(_.stage == DealStage.Review)} awaiting review", 18.0, bold = true),
        WorkspaceStyle.button("New financial review", () => startImport(), primary = true)), WorkspaceStyle.text("Find a deal", 16.0, bold = true), search,
      filters, count, rows)
  }

  private def startImport(): Unit = {
    generation += 1
    val panel = new ImportPanel(width, showDeal)
    importer = Some(panel)
    detail = None
    children @= Vector(WorkspaceStyle.button("Back to deals", () => if (canLeave) portfolio()), panel)
  }

  private def open(id: String): Unit = {
    generation += 1
    val current = generation
    ApiClient.deal(id) {
      case Right(deal) if current == generation => showDeal(deal, overview = true)
      case Left(error) if current == generation => dom.window.alert(error)
      case _ => ()
    }
  }

  private def showDeal(deal: WorkspaceDeal): Unit = showDeal(deal, overview = false)

  private def showDeal(deal: WorkspaceDeal, overview: Boolean): Unit = {
    generation += 1
    importer = None
    val panel = new DealPanel(width, deal, () => portfolio(), if (overview) "Overview" else "Financials")
    detail = Some(panel)
    children @= Vector(panel)
  }
  portfolio()
}
