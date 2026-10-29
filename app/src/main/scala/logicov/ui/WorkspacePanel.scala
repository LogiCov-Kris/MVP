package logicov.ui

import everyui.component.{Column, Container, Select, TextInput}
import everyui.layout.VerticalLayout
import logicov.workspace.{DealStage, DealSummary, WorkspaceDeal}
import org.scalajs.dom
import reactify.*

class WorkspacePanel(width: () => Double) extends Container {
  layout @= Some(VerticalLayout(20.0))
  size.width := width()
  private var importer: Option[ImportPanel] = None
  private var detail: Option[DealPanel] = None
  private var generation = 0
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
    search.size.width := width()
    val stages = new Select(List("All deals", "Needs review", "Approved", "Generating", "Completed", "Failed"))
    stages.size.width := math.min(280.0, width())
    stages.size.height @= 46.0
    stages.node.el.querySelector("select").setAttribute("aria-label", "Deal status")
    val rows = Column(12.0)()
    def filter(): Unit = {
      val selected = List(None, Some(DealStage.Review), Some(DealStage.Approved), Some(DealStage.Generating), Some(DealStage.Completed), Some(DealStage.Failed))(stages.selected())
      val found = deals.filter(d => d.title.toLowerCase.contains(search.value().toLowerCase) && selected.forall(_ == d.stage))
      rows.children @= (if (found.isEmpty) Vector(WorkspaceStyle.card(width,
        WorkspaceStyle.text(if (deals.isEmpty) "Your first deal starts here" else "No matching deals", 20.0, bold = true),
        WorkspaceStyle.paragraph("Import financial statements to build a cited grid, document your review and prepare a memo.", () => width() - 48.0)))
      else found.map { deal => WorkspaceStyle.card(width,
        WorkspaceStyle.text(deal.title, 20.0, bold = true),
        WorkspaceStyle.paragraph(s"${deal.stage} · ${deal.values} cited values · ${deal.issues} open review items · Revision ${deal.revision}", () => width() - 48.0, 13.0),
        WorkspaceStyle.button("Open deal", () => open(deal.id))) }.toVector)
    }
    search.value.attach(_ => filter())
    stages.selected.attach(_ => filter())
    filter()
    children @= Vector(WorkspaceStyle.text("Deal workspace", 30.0, bold = true),
      WorkspaceStyle.paragraph("Financial analysis, review and approval in one place.", width, 15.0),
      WorkspaceStyle.card(width, WorkspaceStyle.text(s"${deals.size} deals  ·  ${deals.count(_.stage == DealStage.Review)} awaiting review", 18.0, bold = true),
        WorkspaceStyle.button("New financial review", () => startImport(), primary = true)), search, stages, rows)
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
      case Right(deal) if current == generation => showDeal(deal)
      case Left(error) if current == generation => dom.window.alert(error)
      case _ => ()
    }
  }

  private def showDeal(deal: WorkspaceDeal): Unit = {
    generation += 1
    importer = None
    val panel = new DealPanel(width, deal, () => portfolio())
    detail = Some(panel)
    children @= Vector(panel)
  }
  portfolio()
}
