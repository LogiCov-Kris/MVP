package logicov.ui

import everyui.Color
import everyui.component.{Button, ButtonStyle, Column, Component, Container, TextView}
import everyui.component.types.{FontWeight, Semantics, WhiteSpace}
import everyui.font.MaterialIcon
import everyui.theme.{Palette, Theme}
import logicov.workspace.DealStage
import reactify.*

object WorkspaceStyle {
  val Navy: Color = Color.fromHex("242453")
  val Teal: Color = Color.fromHex("2563EB")
  def Soft: Color = if (Appearance.dark()) Color.fromHex("293857") else Color.fromHex("EEF3FF")

  def quantity(count: Int, noun: String): String = s"$count $noun${if (count == 1) "" else "s"}"

  def stageLabel(stage: DealStage): String = stage match {
    case DealStage.Review => "Needs review"
    case DealStage.Approved => "Ready to generate"
    case DealStage.Generating => "Preparing memo"
    case DealStage.Completed => "Memo ready"
    case DealStage.Submitted => "Submitted for Approval"
    case DealStage.Failed => "Needs attention"
  }

  def initialize(): Unit = Appearance.initialize()

  def applyPalette(dark: Boolean): Unit = {
    Theme.palette @= (if (dark) Palette.dark.copy(
      background = Color.fromHex("101522"), surface = Color.fromHex("1A2233"),
      raised = Color.fromHex("222D40"), control = Color.fromHex("253148"), controlLit = Color.fromHex("30405A"),
      border = Color.fromHex("42506A"), text = Color.fromHex("E5EAF4"), muted = Color.fromHex("AAB8CE"),
      accent = Color.fromHex("81A9FF"), accentLit = Color.fromHex("ADC6FF")) else Palette.light.copy(background = Color.fromHex("F5F7FA"), surface = Color.White,
      raised = Color.White, control = Color.fromHex("EEF2F6"), controlLit = Color.fromHex("F2F7F8"),
      border = Color.fromHex("DDE4EB"), text = Color.fromHex("203344"), muted = Color.fromHex("657687"),
      accent = Teal, accentLit = Color.fromHex("1D4ED8"), success = Color.fromHex("16836D")))
  }

  def text(value: String, points: Double = 14.0, muted: Boolean = false, bold: Boolean = false): TextView = {
    val view = TextView(value, points)
    view.color := (if (muted) Theme.muted() else Theme.text())
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
    card.backgroundColor := Theme.surface()
    card.borderWidth @= 1.0
    card.borderColor := Theme.border()
    card.radius @= 12.0
    card
  }
}
