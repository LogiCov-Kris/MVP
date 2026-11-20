package logicov.ui

import everyui.component.{Column, Component, Container, Select, TextInput}
import everyui.layout.VerticalLayout
import logicov.analysis.{CovenantKind, FacilityAction}
import logicov.rating.Support
import logicov.workspace.*
import org.scalajs.dom
import reactify.*

import scala.util.Try

class CreditSetupPanel(width: () => Double, deal: WorkspaceDeal, busy: () => Boolean, changed: () => Unit,
                       canLeave: () => Boolean, submit: CreditSetupRequest => Unit, upload: DocumentRequest => Unit,
                       error: String => Unit, saveDraft: ProfileDraftRequest => Unit) extends Container {
  layout @= Some(VerticalLayout(18.0))
  size.width := width()
  private val inner = () => width() - 48.0
  private val suggestions = ProfilePrefill.suggestions(deal)
  private val initialValues = suggestions.map(s => s.field -> s.value).toMap ++ deal.profileDraft.map(e => e.field -> e.value)
  private var fields = Map.empty[ProfileField, TextInput]
  private var selections = Map.empty[ProfileField, Select]
  private def field(key: ProfileField, label: String, value: String = ""): TextInput = {
    val input = new TextInput(label)
    input.value @= initialValues.getOrElse(key, value)
    fields += key -> input
    input.size.width := inner()
    input.readOnly := busy()
    input.value.attach(_ => changed())
    input
  }
  private def select(key: ProfileField, label: String, values: List[String], index: Int = 0): Select = {
    val input = new Select(values)
    input.selected @= initialValues.get(key).flatMap(_.toIntOption).filter(i => i >= 0 && i < values.size).getOrElse(index)
    selections += key -> input
    input.size.width := inner()
    input.size.height @= 46.0
    input.node.el.querySelector("select").setAttribute("aria-label", label)
    Val(busy()).attach(v => input.node.el.querySelector("select").asInstanceOf[dom.html.Select].disabled = v)
    input.selected.attach(_ => changed())
    input
  }
  private val sources = deal.documents.filterNot(_.name.startsWith("generated-"))
  private val source = select(ProfileField.Document, "Credit profile source", sources.map(_.name))
  private val citation = field(ProfileField.Citation, "Source pages or section")
  private val identifier = field(ProfileField.Identifier, "Borrower reference", deal.publicIssuer.fold("")(_.ticker))
  private val year = field(ProfileField.Year, "Latest fiscal year", deal.grid.values.filter(_.period.label.startsWith("FY")).map(_.period.end.getYear).maxOption
    .orElse(deal.grid.values.map(_.period.end.getYear).maxOption).fold("")(_.toString))
  private val ebitda = field(ProfileField.Ebitda, "Three annual EBITDA amounts, oldest first (USD millions)")
  private val facilityKind = field(ProfileField.FacilityKind, "Facility type")
  private val action = select(ProfileField.Action, "Facility action", FacilityAction.values.toList.map(_.label))
  private val maturity = field(ProfileField.Maturity, "Maturity date or stated target")
  private val agent = field(ProfileField.Agent, "Administrative agent or direct lender")
  private val commitment = field(ProfileField.Commitment, "Facility commitment (USD millions)")
  private val availability = field(ProfileField.Availability, "Undrawn availability (USD millions)")
  private val covenant = select(ProfileField.Covenant, "Covenant type", List("Select the facility covenant", "Maximum net leverage", "Maximum debt to capitalization"))
  private val threshold = field(ProfileField.Threshold, "Covenant threshold (multiple or fraction)")
  private val concentration = field(ProfileField.Concentration, "Largest concentration (%)")
  private val basis = field(ProfileField.ConcentrationBasis, "Concentration basis")
  private val linked = field(ProfileField.PriceLinked, "Commodity-price-linked share (%)")
  private val customer = select(ProfileField.Customer, "Customer concentration", List("No customer above 10%", "Customer above 10%"))
  private val support = select(ProfileField.Support, "Ownership support", Support.values.toList.map(_.label), Support.values.indexOf(Support.Neutral))
  private val supportBasis = field(ProfileField.SupportBasis, "Ownership support rationale")
  private val reason = field(ProfileField.Reason, "Profile review explanation")
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
    if (covenant.selected() == 0) error("Select the actual facility covenant before building the analysis.")
    else Try(CreditSetupRequest(deal.revision, identifier.value(), year.value().toInt, facilityKind.value(), FacilityAction.values(action.selected()),
      maturity.value(), agent.value(), BigDecimal(commitment.value()), BigDecimal(availability.value()), CovenantKind.values(covenant.selected() - 1),
      BigDecimal(threshold.value()), ebitda.value().split(",").toList.map(s => BigDecimal(s.trim)), BigDecimal(concentration.value()) / 100,
      BigDecimal(linked.value()) / 100, basis.value(), Support.values(support.selected()), supportBasis.value(), customer.selected() == 1,
      sources(source.selected()).id, citation.value(), reason.value())).toEither match {
      case Left(_) => error("Complete the fiscal year, amounts, percentages and source selection with valid values.")
      case Right(request) => submit(request)
    }
  }, primary = true)
  save.enabled := !busy() && deal.publicIssuer.flatMap(_.methodologyIssue).isEmpty
  private val draft = WorkspaceStyle.button("Save profile draft", () => saveDraft(ProfileDraftRequest(deal.revision,
    fields.toList.map((key, input) => ProfileEntry(key, input.value())) ++ selections.toList.map((key, input) => ProfileEntry(key, input.selected().toString)))))
  draft.enabled := !busy()
  private val disclosurePanel = Column(12.0)(WorkspaceStyle.paragraph("Loading disclosed facility terms...", inner))
  if (deal.publicIssuer.nonEmpty) ApiClient.creditDisclosures(deal.id) {
    case Left(message) => disclosurePanel.children @= Vector(WorkspaceStyle.paragraph(message, inner))
    case Right(disclosures) => disclosurePanel.children @= (if (disclosures.isEmpty)
      Vector(WorkspaceStyle.paragraph("No unambiguous facility terms were identified in the latest retained filing. Review the source documents and enter the supported terms below.", inner))
    else disclosures.map { disclosure =>
      val use = WorkspaceStyle.button("Use these disclosed terms", () => {
        List(ProfileField.FacilityKind, ProfileField.Maturity, ProfileField.Agent, ProfileField.Commitment, ProfileField.Availability, ProfileField.Threshold)
          .foreach(key => fields.get(key).foreach(_.value @= ""))
        covenant.selected @= 0
        disclosure.suggestions.foreach { entry =>
          fields.get(entry.field).foreach(_.value @= entry.value)
          selections.get(entry.field).foreach(input => entry.value.toIntOption.foreach(v => input.selected @= (if (entry.field == ProfileField.Covenant) v + 1 else v)))
        }
        source.selected @= sources.indexWhere(_.id == disclosure.documentId)
        citation.value @= "Credit agreement disclosure in " + disclosure.documentName
        changed()
      })
      use.enabled := !busy()
      val expanded = Var(false)
      val excerpt = WorkspaceStyle.paragraph(disclosure.excerpt, inner, 13.0)
      excerpt.visible := expanded()
      val toggle = WorkspaceStyle.button("Show filing excerpt", () => expanded @= !expanded())
      expanded.attach(value => toggle.node.el.setAttribute("aria-expanded", value.toString))
      Column(10.0)(WorkspaceStyle.paragraph(disclosure.documentName, inner, 13.0),
        WorkspaceStyle.paragraph(disclosure.suggestions.filter(s => Set(ProfileField.Agent, ProfileField.Commitment, ProfileField.Maturity).contains(s.field))
          .map(s => s"${s.field}: ${s.value}${if (s.field == ProfileField.Commitment) " USD millions" else ""}").mkString("\n"), inner),
        toggle, excerpt,
        WorkspaceStyle.paragraph("Choose the facility being analyzed, review the cited terms, then amend them for your proposed deal. Ambiguous or unsupported terms remain blank.", inner), use)
    }.toVector)
  } else disclosurePanel.children @= Vector(WorkspaceStyle.paragraph("Attach and cite the facility document below. SEC disclosures are available for public-company imports.", inner))
  private val derived = suggestions.filter(_.field == ProfileField.Ebitda).map(s => WorkspaceStyle.paragraph(s.explanation + "\n" + s.sources.map(_.describe).mkString("\n"), inner, 12.0))
  children @= Vector(
    WorkspaceStyle.card(width, WorkspaceStyle.text("Complete the credit profile", 20.0, bold = true),
      WorkspaceStyle.paragraph(deal.publicIssuer.flatMap(_.methodologyIssue).getOrElse("Confirm the retained financials and complete the sourced inputs below. Missing grid values can be added in Financials."), inner),
      WorkspaceStyle.paragraph("Add sourced facility terms and analyst judgments to run the risk rating and actual covenant test. The grid must include three fiscal years of cash flow and capital expenditures, plus the latest debt, cash, equity, revenue, interest and dividends.", inner),
      WorkspaceStyle.paragraph("Save a draft at any point, then return to complete it. Amounts are USD millions; do not enter a missing value as zero.", inner), draft, attach, source, citation),
    WorkspaceStyle.card(width, WorkspaceStyle.text("Disclosed facility terms", 18.0, bold = true), disclosurePanel),
    WorkspaceStyle.card(width, (Vector[Component](WorkspaceStyle.text("Borrower and earnings", 18.0, bold = true), identifier, year, ebitda) ++ derived)*),
    WorkspaceStyle.card(width, WorkspaceStyle.text("Facility and covenant", 18.0, bold = true), facilityKind, action, maturity, agent, commitment, availability, covenant, threshold,
      WorkspaceStyle.paragraph("Enter 4.0 for a 4.0x leverage maximum or 0.60 for a 60% debt-to-capitalization maximum. Use the actual facility covenant.", inner, 12.0)),
    WorkspaceStyle.card(width, WorkspaceStyle.text("Risk judgments", 18.0, bold = true), concentration, basis, linked, customer, support, supportBasis, reason, save))
}
