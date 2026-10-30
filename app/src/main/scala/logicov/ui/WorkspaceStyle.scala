package logicov.ui

import everyui.Color
import everyui.component.{Button, ButtonStyle, Column, Component, Container, TextView}
import everyui.component.types.{FontWeight, Semantics, WhiteSpace}
import everyui.font.MaterialIcon
import everyui.theme.{Palette, Theme}
import logicov.workspace.DealStage
import reactify.*

object WorkspaceStyle {
  val Navy: Color = Color.fromHex("142B3C")
  val Teal: Color = Color.fromHex("087F79")
  val Soft: Color = Color.fromHex("EFF6F5")

  def quantity(count: Int, noun: String): String = s"$count $noun${if (count == 1) "" else "s"}"

  def stageLabel(stage: DealStage): String = stage match {
    case DealStage.Review => "Needs review"
    case DealStage.Approved => "Ready to generate"
    case DealStage.Generating => "Preparing memo"
    case DealStage.Completed => "Memo ready"
    case DealStage.Failed => "Needs attention"
  }

  def initialize(): Unit = {
    Theme.palette @= Palette.light.copy(background = Color.fromHex("F5F7FA"), surface = Color.White,
      raised = Color.White, control = Color.fromHex("EEF2F6"), controlLit = Color.fromHex("F2F7F8"),
      border = Color.fromHex("DDE4EB"), text = Color.fromHex("203344"), muted = Color.fromHex("657687"),
      accent = Teal, accentLit = Color.fromHex("096C67"), success = Teal)
  }

  def text(value: String, points: Double = 14.0, muted: Boolean = false, bold: Boolean = false): TextView = {
    val view = TextView(value, points, if (muted) Theme.muted() else Theme.text())
    if (bold) view.font.weight @= FontWeight.SemiBold
    view
  }

  def paragraph(value: String, width: () => Double, points: Double = 14.0, muted: Boolean = true): TextView = {
    val view = text(value, points, muted)
    view.size.width := width()
    view.whiteSpace @= WhiteSpace.PreLine
    view.wrapAnywhere @= true
    view
  }

  def button(label: String, action: () => Unit, primary: Boolean = false, icon: Option[MaterialIcon] = None): Button = {
    val button = new Button(label, action, if (primary) ButtonStyle.Filled else ButtonStyle.Outlined, icon, 14.0)
    button.radius @= 7.0
    button.size.height @= 42.0
    button.semantics := Semantics.button(button.label())
    button.focusable := button.enabled()
    button.node.el.setAttribute("aria-disabled", (!button.enabled()).toString)
    button.enabled.attach(enabled => button.node.el.setAttribute("aria-disabled", (!enabled).toString))
    button
  }

  def card(width: () => Double, children: Component*): Container = {
    val card = Column(16.0, padding = 24.0)(children*)
    card.size.width := width()
    card.backgroundColor @= Color.White
    card.borderWidth @= 1.0
    card.borderColor := Theme.border()
    card.radius @= 12.0
    card
  }
}
