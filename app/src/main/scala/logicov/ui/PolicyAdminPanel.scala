package logicov.ui

import everyui.component.{Column, Component, Container, Select, TextInput}
import everyui.layout.VerticalLayout
import logicov.workspace.*
import org.scalajs.dom
import reactify.*

import scala.util.Try

class PolicyAdminPanel(width: () => Double) extends Container {
  layout @= Some(VerticalLayout(18.0))
  size.width := width()
  private val inner = () => width() - 48.0
  private val busy = Var(false)
  private var dirty = false
  private var current = WorkspacePolicy()
  private val message = Var("")
  private val body = Column(16.0)()
  def processing: Boolean = busy()
  def canLeave: Boolean = !busy() && (!dirty || dom.window.confirm("Discard unsaved policy changes?"))
  private def field(label: String, value: String): TextInput = {
    val input = new TextInput(label)
    input.size.width := inner()
    input.value @= value
    input.readOnly := busy()
    input.value.attach(_ => dirty = true)
    input
  }
  private def select(label: String, values: List[String], index: Int): Select = {
    val input = new Select(values)
    input.size.width := inner()
    input.size.height @= 46.0
    input.selected @= index
    input.node.el.querySelector("select").setAttribute("aria-label", label)
    input.selected.attach(_ => dirty = true)
    input
  }
  private def render(): Unit = {
    val mode = select("Workspace policy basis", List("Golden Source only", "Institution policy"), if (current.policy.nonEmpty) 1 else 0)
    val name = field("Policy name", current.policy.fold("")(_.name))
    val version = field("Policy version", current.policy.fold("")(_.version))
    val source = field("Policy source reference", current.policy.fold("")(_.source))
    val reason = field("Reason for policy change", "")
    val rules = Column(14.0)()
    var readRules = Vector.empty[() => PolicyRule]
    def addRule(rule: PolicyRule): Unit = {
      val description = field("Rule description", rule.description)
      val metric = select("Policy metric", PolicyMetric.values.toList.map(_.toString), rule.metric.ordinal)
      val operator = select("Policy comparison", List("At most", "At least"), rule.operator.ordinal)
      val threshold = field("Policy threshold", rule.threshold.toString)
      val read = () => PolicyRule(rule.id, description.value(), PolicyMetric.values(metric.selected()), PolicyOperator.values(operator.selected()), BigDecimal(threshold.value()))
      val row = Column(10.0)()
      val remove = WorkspaceStyle.button("Remove rule", () => {
        readRules = readRules.filterNot(_ eq read)
        rules.children @= rules.children().filterNot(_ eq row)
        dirty = true
      })
      row.children @= Vector(description, metric, operator, threshold, remove)
      rules.children @= rules.children() :+ row
      readRules :+= read
    }
    current.policy.toList.flatMap(_.rules).foreach(addRule)
    val add = WorkspaceStyle.button("Add rule", () => {
      addRule(PolicyRule(s"rule-${System.currentTimeMillis()}", "", PolicyMetric.NetLeverage, PolicyOperator.AtMost, BigDecimal(4)))
      dirty = true
    })
    val policyForm = Column(12.0)(name, version, source, rules, add)
    policyForm.visible := mode.selected() == 1
    val save = WorkspaceStyle.button("Save workspace policy", () => {
      val parsed = Try {
        val policy = if (mode.selected() == 0) None else Some(PolicySet(name.value(), version.value(), source.value(), readRules.map(_()).toList))
        policy.foreach(_.validate())
        require(reason.value().trim.nonEmpty, "Explain the policy change")
        current.copy(policy = policy, reason = reason.value())
      }.toEither
      parsed match {
        case Left(error) => message @= Option(error.getMessage).getOrElse("Check the policy inputs")
        case Right(request) =>
          busy @= true
          ApiClient.saveWorkspacePolicy(request) {
            case Left(error) => busy @= false; message @= error
            case Right(updated) => busy @= false; dirty = false; current = updated; message @= "Workspace policy saved. It will apply when inputs are next approved."; render()
          }
      }
    }, primary = true)
    save.enabled := !busy()
    body.children @= Vector(WorkspaceStyle.card(width, WorkspaceStyle.text("Workspace underwriting policy", 22.0, bold = true),
      WorkspaceStyle.paragraph("Configure one policy for this workspace. Analysts do not select a policy on each deal. Golden Source guidance applies until an institution overlay is configured. Approved drafts retain their policy snapshot; changes require renewed approval before generation.", inner),
      mode, policyForm, reason, save))
  }
  private val status = WorkspaceStyle.paragraph("", width)
  message.attach { value =>
    status.content @= value
    if (value.nonEmpty) dom.window.setTimeout(() => status.node.el.scrollIntoView(), 50)
  }
  status.node.el.setAttribute("role", "status")
  children @= Vector(status, body)
  busy @= true
  ApiClient.workspacePolicy {
    case Left(error) => busy @= false; message @= error
    case Right(value) => busy @= false; current = value; render()
  }
}
