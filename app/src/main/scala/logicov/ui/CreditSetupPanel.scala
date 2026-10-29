package logicov.ui

import everyui.component.{Column, Container, Select, TextInput}
import everyui.layout.VerticalLayout
import logicov.analysis.{CovenantKind, FacilityAction}
import logicov.rating.Support
import logicov.workspace.{CreditSetupRequest, DocumentRequest, WorkspaceDeal}
import org.scalajs.dom
import reactify.*

import scala.util.Try

class CreditSetupPanel(width: () => Double, deal: WorkspaceDeal, busy: () => Boolean, changed: () => Unit,
                       canLeave: () => Boolean, submit: CreditSetupRequest => Unit, upload: DocumentRequest => Unit,
                       error: String => Unit) extends Container {
  layout @= Some(VerticalLayout(18.0))
  size.width := width()
  private val inner = () => width() - 48.0
  private def field(label: String, value: String = ""): TextInput = {
    val input = new TextInput(label)
    input.value @= value
    input.size.width := inner()
    input.readOnly := busy()
    input.value.attach(_ => changed())
    input
  }
  private def select(label: String, values: List[String], index: Int = 0): Select = {
    val input = new Select(values)
    input.selected @= index
    input.size.width := inner()
    input.size.height @= 46.0
    input.node.el.querySelector("select").setAttribute("aria-label", label)
    Val(busy()).attach(v => input.node.el.querySelector("select").asInstanceOf[dom.html.Select].disabled = v)
    input.selected.attach(_ => changed())
    input
  }
  private val sources = deal.documents.filterNot(_.name.startsWith("generated-"))
  private val source = select("Credit profile source", sources.map(_.name))
  private val citation = field("Source pages or section")
  private val identifier = field("Borrower reference")
  private val year = field("Latest fiscal year", deal.grid.values.map(_.period.end.getYear).maxOption.fold("")(_.toString))
  private val ebitda = field("Three annual EBITDA amounts, oldest first (USD millions)")
  private val facilityKind = field("Facility type")
  private val action = select("Facility action", FacilityAction.values.toList.map(_.label))
  private val maturity = field("Maturity date or stated target")
  private val agent = field("Administrative agent or direct lender")
  private val commitment = field("Facility commitment (USD millions)")
  private val availability = field("Undrawn availability (USD millions)")
  private val covenant = select("Covenant type", List("Maximum net leverage", "Maximum debt to capitalization"))
  private val threshold = field("Covenant threshold (multiple or fraction)")
  private val concentration = field("Largest concentration (%)")
  private val basis = field("Concentration basis")
  private val linked = field("Commodity-price-linked share (%)")
  private val customer = select("Customer concentration", List("No customer above 10%", "Customer above 10%"))
  private val support = select("Ownership support", Support.values.toList.map(_.label), Support.values.indexOf(Support.Neutral))
  private val supportBasis = field("Ownership support rationale")
  private val reason = field("Profile review explanation")
  private val attach = WorkspaceStyle.button("Attach supporting document", () => if (canLeave()) {
    val picker = dom.document.createElement("input").asInstanceOf[dom.html.Input]
    picker.`type` = "file"
    picker.accept = ".pdf,.csv,.xls,.xlsx,.txt,.md,.json"
    picker.onchange = _ => if (picker.files.length == 1) {
      val file = picker.files(0)
      if (file.size > 5 * 1024 * 1024) error("Supporting documents must be 5 MB or smaller.")
      else {
        val reader = new dom.FileReader()
        reader.onerror = _ => error("The supporting document could not be read.")
        reader.onload = _ => upload(DocumentRequest(deal.revision, file.name, reader.result.toString.split(",", 2).last))
        reader.readAsDataURL(file)
      }
    }
    picker.click()
  })
  attach.enabled := !busy()
  private val save = WorkspaceStyle.button("Build credit analysis", () => {
    Try(CreditSetupRequest(deal.revision, identifier.value(), year.value().toInt, facilityKind.value(), FacilityAction.values(action.selected()),
      maturity.value(), agent.value(), BigDecimal(commitment.value()), BigDecimal(availability.value()), CovenantKind.values(covenant.selected()),
      BigDecimal(threshold.value()), ebitda.value().split(",").toList.map(s => BigDecimal(s.trim)), BigDecimal(concentration.value()) / 100,
      BigDecimal(linked.value()) / 100, basis.value(), Support.values(support.selected()), supportBasis.value(), customer.selected() == 1,
      sources(source.selected()).id, citation.value(), reason.value())).toEither match {
      case Left(_) => error("Complete the fiscal year, amounts, percentages and source selection with valid values.")
      case Right(request) => submit(request)
    }
  }, primary = true)
  save.enabled := !busy()
  children @= Vector(
    WorkspaceStyle.card(width, WorkspaceStyle.text("Complete the credit profile", 20.0, bold = true),
      WorkspaceStyle.paragraph("Add sourced facility terms and analyst judgments to run the risk rating and actual covenant test. The grid must include three fiscal years of cash flow and capital expenditures, plus the latest debt, cash, equity, revenue, interest and dividends.", inner),
      WorkspaceStyle.paragraph("Attach supporting documents before filling out this form. Amounts are USD millions; do not enter a missing value as zero.", inner), attach, source, citation),
    WorkspaceStyle.card(width, WorkspaceStyle.text("Borrower and earnings", 18.0, bold = true), identifier, year, ebitda,
      WorkspaceStyle.paragraph("For a latest year of 2025, provide EBITDA for 2023, 2024 and 2025 in that order.", inner, 12.0)),
    WorkspaceStyle.card(width, WorkspaceStyle.text("Facility and covenant", 18.0, bold = true), facilityKind, action, maturity, agent, commitment, availability, covenant, threshold,
      WorkspaceStyle.paragraph("Enter 4.0 for a 4.0x leverage maximum or 0.60 for a 60% debt-to-capitalization maximum. Use the actual facility covenant.", inner, 12.0)),
    WorkspaceStyle.card(width, WorkspaceStyle.text("Risk judgments", 18.0, bold = true), concentration, basis, linked, customer, support, supportBasis, reason, save))
}
