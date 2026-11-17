package logicov.ui

import everyui.component.{Container, Select, TextInput}
import everyui.layout.VerticalLayout
import logicov.workspace.{CreditRequirements, GridAdditionRequest, WorkspaceDeal}
import org.scalajs.dom
import reactify.*

import scala.util.Try

class MissingFinancialPanel(width: () => Double, deal: WorkspaceDeal, busy: () => Boolean,
                            submit: GridAdditionRequest => Unit, error: String => Unit) extends Container {
  layout @= Some(VerticalLayout(14.0))
  size.width := width()
  private val inner = () => width() - 48.0
  private val latest = deal.grid.values.filter(_.period.label.startsWith("FY")).map(_.period.end.getYear).maxOption
  private val missing = latest.toList.flatMap(CreditRequirements.required).filter((item, period) => deal.grid.get(item, period).isEmpty)
  private val sources = deal.documents.filterNot(_.name.startsWith("generated-"))
  private def select(label: String, values: List[String]): Select = {
    val input = new Select(values)
    input.size.width := inner()
    input.size.height @= 46.0
    input.node.el.querySelector("select").setAttribute("aria-label", label)
    Val(busy()).attach(v => input.node.el.querySelector("select").asInstanceOf[dom.html.Select].disabled = v)
    input
  }
  private def field(label: String): TextInput = {
    val input = new TextInput(label)
    input.size.width := inner()
    input.readOnly := busy()
    input
  }
  private val item = select("Missing financial line", missing.map((line, period) => s"$line · ${period.label}"))
  private val source = select("Financial value source", sources.map(_.name))
  private val amount = field("Missing amount (USD millions)")
  private val citation = field("Financial value source page or section")
  private val reason = field("Financial value evidence and explanation")
  private val save = WorkspaceStyle.button("Add cited financial value", () => {
    (missing.lift(item.selected()), sources.lift(source.selected()), Try(BigDecimal(amount.value())).toOption) match {
      case (Some((line, period)), Some(document), Some(value)) => submit(GridAdditionRequest(deal.revision, line, period, value, document.id, citation.value(), reason.value()))
      case _ => error("Choose a missing line and source, and enter a valid amount.")
    }
  })
  save.enabled := !busy() && missing.nonEmpty && sources.nonEmpty
  children @= (if (deal.record.isEmpty && missing.nonEmpty && deal.publicIssuer.flatMap(_.methodologyIssue).isEmpty) Vector(WorkspaceStyle.card(width,
    WorkspaceStyle.text("Complete missing financial values", 18.0, bold = true),
    WorkspaceStyle.paragraph(s"${missing.size} required values were not extracted. Review the retained filings and add only values supported by a cited disclosure. A reported zero needs evidence too.", inner),
    item, source, amount, citation, reason, save)) else Vector.empty)
}
