package logicov.ui

import everyui.component.{Container, TextInput, TextView}
import everyui.component.types.WhiteSpace
import everyui.layout.VerticalLayout
import everyui.theme.Theme
import logicov.publiccompany.{IssuerLookup, PublicIntakeRequest, PublicIssuer}
import logicov.workspace.WorkspaceDeal
import reactify.*

import java.util.UUID

class PublicCompanyPanel(width: () => Double, saved: WorkspaceDeal => Unit) extends Container {
  layout @= Some(VerticalLayout(18.0))
  size.width := width()
  private val inner = () => width() - 48.0
  private val busy = Var(false)
  private val message = Var("")
  private var requestId = UUID.randomUUID().toString
  private val query = new TextInput("Ticker or SEC CIK")
  query.size.width := inner()
  query.readOnly := busy()
  private val status = TextView.bound(message(), 13.0, Theme.text())
  status.size.width := inner()
  status.whiteSpace @= WhiteSpace.Normal
  status.node.el.setAttribute("role", "status")
  private val confirmation = new Container
  confirmation.layout @= Some(VerticalLayout(14.0))
  confirmation.size.width := inner()
  private val lookup = WorkspaceStyle.button("Find company", () => find(), primary = true)
  lookup.enabled := !busy() && query.value().trim.nonEmpty
  lookup.label := (if (busy()) "Contacting SEC..." else "Find company")
  query.value.attach { _ => confirmation.children @= Vector.empty; message @= ""; requestId = UUID.randomUUID().toString }
  children @= Vector(WorkspaceStyle.card(width,
    WorkspaceStyle.text("Public company", 22.0, bold = true),
    WorkspaceStyle.paragraph("Find the issuer, confirm its identity and import its reported financials from SEC filings.", inner),
    query, lookup, status, confirmation),
    WorkspaceStyle.card(width,
      WorkspaceStyle.text("What happens next", 16.0, bold = true),
      WorkspaceStyle.paragraph("Financial values and source documents are saved for review. Missing disclosures remain missing. You will confirm facility terms, EBITDA and credit judgments before generating a memo.", inner)))

  def processing: Boolean = busy()

  private def find(): Unit = {
    busy @= true
    message @= "Looking up the SEC issuer..."
    confirmation.children @= Vector.empty
    ApiClient.publicLookup(IssuerLookup(query.value())) {
      case Left(error) => busy @= false; message @= error
      case Right(value) =>
        busy @= false
        message @= "Confirm this is the borrower you intend to analyze."
        val begin = WorkspaceStyle.button("Import SEC financials", () => start(value), primary = true)
        begin.enabled := !busy()
        val heading = WorkspaceStyle.text(value.name, 18.0, bold = true)
        heading.size.width := inner()
        heading.whiteSpace @= WhiteSpace.Normal
        confirmation.children @= Vector(
          heading,
          WorkspaceStyle.paragraph(s"Ticker: ${value.ticker} · SEC CIK: ${value.cik}", inner),
          WorkspaceStyle.paragraph(value.methodologyIssue.getOrElse("Reported USD financials will be imported with citations. No risk rating or lending recommendation is assigned during intake."), inner), begin)
    }
  }

  private def start(value: PublicIssuer): Unit = {
    busy @= true
    message @= "Starting your saved import. You can return to it from the portfolio."
    ApiClient.publicCreate(PublicIntakeRequest(value.cik, requestId)) {
      case Left(error) => busy @= false; message @= error
      case Right(deal) => busy @= false; saved(deal)
    }
  }
}
