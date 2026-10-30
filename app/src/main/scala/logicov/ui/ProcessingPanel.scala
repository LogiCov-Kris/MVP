package logicov.ui

import everyui.Color
import everyui.component.{Column, Container}
import logicov.workspace.{GenerationPhase, GenerationProgress, MemoSections}
import reactify.*

class ProcessingPanel(width: () => Double, title: String, progress: Option[GenerationProgress] = None) extends Container {
  size.width := width()
  backgroundColor @= Color.fromHex("171637")
  radius @= 14.0
  node.el.style.background = "linear-gradient(135deg,#171637,#20265c 65%,#173d7c)"
  val heading = WorkspaceStyle.text(title, 28.0, bold = true)
  heading.color @= Color.White
  val explanation = WorkspaceStyle.paragraph("Your work is saved as each stage completes. Status reflects actual processing.", () => width() - 64.0)
  explanation.color @= Color.fromHex("BFCBF2")
  val lines = progress match {
    case None => List("✓  Statement selected", "●  Uploading and extracting financial data", "○  Data verification")
    case Some(value) =>
      List("✓  Approved financial inputs") ++ value.titles.map(t => (if (value.sections.contains(t)) "✓  " else "○  ") + t) ++
        List((if (value.phase == GenerationPhase.Printing || value.phase == GenerationPhase.Packaging) "●  " else "○  ") + "Prepare memo PDF",
          (if (value.phase == GenerationPhase.Packaging) "●  " else "○  ") + "Package source citations")
  }
  val steps = lines.map { line =>
    val text = WorkspaceStyle.paragraph(line, () => width() - 64.0, 14.0, muted = false)
    text.color @= Color.fromHex("D6E1FF")
    text
  }
  val bar = new Container
  bar.size.width := width() - 64.0
  bar.size.height @= 5.0
  bar.radius @= 3.0
  bar.backgroundColor @= Color.fromHex("353963")
  bar.node.el.setAttribute("role", "progressbar")
  bar.node.el.setAttribute("aria-label", title)
  val fill = new Container
  fill.size.height @= 5.0
  fill.backgroundColor @= Color.fromHex("5791FF")
  val fraction = progress.fold(0.0)(p => (1.0 + p.sections.size + (if (p.phase == GenerationPhase.Packaging) 1.0 else 0.0)) / (p.titles.size + 3.0))
  fill.size.width := bar.size.width() * fraction
  if (progress.nonEmpty) bar.node.el.setAttribute("aria-valuenow", (fraction * 100).toInt.toString)
  bar.children += fill
  val content = Column(18.0, padding = 32.0)((Vector(heading, explanation, bar) ++ steps)* )
  content.size.width := width()
  children += content
  size.height := content.size.height()
}
