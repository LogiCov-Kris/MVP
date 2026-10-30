package logicov.ui

import everyui.component.Container
import fabric.rw.*
import logicov.grid.GridCorrection
import logicov.workspace.*
import org.scalajs.dom
import reactify.*

import scala.util.Try

class MemoReviewPanel(width: () => Double, deal: WorkspaceDeal, changed: WorkspaceDeal => Unit, initialScroll: Double = 0.0, workingChanged: Boolean => Unit = _ => ()) extends Container {
  size.width := width()
  size.height @= 820.0
  private var working = false
  private var dirty = false
  private val root = dom.document.createElement("div").asInstanceOf[dom.html.Div]
  root.className = "lc-memo"
  root.style.cssText = "position:absolute;inset:0;"
  node.el.appendChild(root)
  def scrollPosition: Double = Option(root.querySelector("article")).fold(0.0)(_.asInstanceOf[dom.html.Element].scrollTop.toDouble)
  def processing: Boolean = working
  def canLeave: Boolean = !working && (!dirty || dom.window.confirm("Discard unsaved memo changes?"))
  private def element(tag: String, text: String = "", parent: dom.Element = root): dom.html.Element = {
    val el = dom.document.createElement(tag).asInstanceOf[dom.html.Element]
    el.textContent = text
    parent.appendChild(el)
    el
  }
  private def button(label: String, parent: dom.Element)(action: => Unit): dom.html.Button = {
    val b = element("button", label, parent).asInstanceOf[dom.html.Button]
    b.`type` = "button"
    b.onclick = _ => if (!working) action
    b
  }
  private def save[A: RW](route: String, value: A): Unit = {
    working = true
    workingChanged(true)
    val states = root.querySelectorAll("button").map(n => n.asInstanceOf[dom.html.Button]).map(b => b -> b.disabled).toList
    states.foreach((button, _) => button.disabled = true)
    ApiClient.action(deal.id, route, value) {
      case Right(updated) => working = false; workingChanged(false); dirty = false; changed(updated)
      case Left(error) =>
        working = false
        workingChanged(false)
        states.foreach((button, disabled) => button.disabled = disabled)
        dom.window.alert(error)
    }
  }
  private def labelInput(label: String, parent: dom.Element, value: String = ""): dom.html.Input = {
    val wrapper = element("label", label, parent)
    val input = element("input", "", wrapper).asInstanceOf[dom.html.Input]
    input.setAttribute("aria-label", label)
    input.value = value
    input.oninput = _ => dirty = true
    input
  }
  private def render(document: MemoReviewDocument): Unit = {
    root.innerHTML = ""
    element("style", MemoReviewStyle.css)
    val header = element("header")
    element("strong", "Credit memorandum", header)
    val count = document.sections.count(s => deal.sectionReviews.exists(_.section == s.title))
    element("span", s"$count of ${document.sections.size} sections reviewed", header)
    val ready = deal.stage == DealStage.Completed
    if (deal.stage == DealStage.Submitted) element("span", "Submitted for Approval", header).className = "badge"
    else element("p", if (ready) "Read each section, then mark it reviewed. Edits require a fresh memo and review." else "Draft preview. Complete input approval and generate the memo before marking sections reviewed.", header)
    val layout = element("div"); layout.className = "memo-layout"
    val nav = element("nav", "", layout); nav.setAttribute("aria-label", "Memo sections")
    val main = element("article", "", layout); main.setAttribute("aria-label", "Continuous credit memo")
    val cover = element("div", "", main); cover.innerHTML = document.cover
    def navigation(title: String, id: String, reviewed: Boolean): Unit = {
      val link = element("a", (if (reviewed) "✓  " else "") + title, nav).asInstanceOf[dom.html.Anchor]
      link.href = "#" + id
      link.onclick = e => { e.preventDefault(); Option(main.querySelector("#" + id)).foreach(_.scrollIntoView()) }
    }
    document.sections.zipWithIndex.foreach { (section, index) =>
      val id = s"memo-section-$index"
      val reviewed = deal.sectionReviews.exists(_.section == section.title)
      navigation(section.title, id, reviewed)
      val body = element("section", "", main); body.id = id
      val rendered = element("div", "", body)
      rendered.innerHTML = section.html.replace("href=\"#", "href=\"#evidence-").replace("#evidence-evidence-", "#evidence-")
      val controls = element("div", "", body); controls.className = "section-actions"
      val edit = button("Edit " + section.title, controls) {
        if (canLeave) {
          val existing = body.querySelector(".inline-editor")
          if (existing == null) {
            root.querySelectorAll(".inline-editor").foreach(n => n.parentNode.removeChild(n))
            dirty = false
            editor(section, document.bindings, body)
          }
        }
      }
      edit.disabled = deal.stage == DealStage.Generating
      val mark = button(if (reviewed) "Reviewed · undo" else "Mark section reviewed", controls) {
        if (!dirty) save("section-review", SectionReviewRequest(deal.revision, section.title, !reviewed))
        else dom.window.alert("Save or cancel your edits before marking this section reviewed.")
      }
      mark.setAttribute("aria-label", (if (reviewed) "Undo review: " else "Mark reviewed: ") + section.title)
      mark.disabled = !ready
      if (section.title == "Executive Summary") {
        val details = element("details", "", body)
        element("summary", "Edit borrower details", details)
        val name = labelInput("Borrower legal name", details, deal.title)
        val reason = labelInput("Reason for borrower name correction", details)
        button("Save borrower name", details) { save("identity", BorrowerRequest(deal.revision, name.value, reason.value)) }
      }
      if (section.title == "Financial Analysis") financialEditor(body)
    }
    navigation("Source Appendix", "memo-citations", false)
    val appendix = element("section", "", main); appendix.id = "memo-citations"
    element("h2", "Source Appendix", appendix)
    deal.documents.filterNot(_.name.startsWith("generated-")).foreach { doc =>
      val a = element("a", doc.name, element("p", "", appendix)).asInstanceOf[dom.html.Anchor]
      a.href = s"/api/deals/${deal.id}/files/${doc.id}"
    }
    val citations = element("div", "", appendix); citations.innerHTML = document.appendix
    if (ready && count == document.sections.size) {
      val submit = element("div", "", root); submit.className = "submit-bar"
      val label = element("label", "Recommendation", submit)
      val select = element("select", "", label).asInstanceOf[dom.html.Select]
      select.setAttribute("aria-label", "Committee recommendation")
      List("Choose recommendation", "Approve", "Conditional", "Decline").foreach(t => element("option", t, select))
      val rationale = labelInput("Recommendation rationale", submit)
      val send = button("Submit to Committee", submit) {
        val choices = List(CreditRecommendation.Pending, CreditRecommendation.Approve, CreditRecommendation.Conditional, CreditRecommendation.Decline)
        if (select.selectedIndex == 0 || rationale.value.trim.isEmpty) dom.window.alert("Choose a recommendation and provide its rationale.")
        else if (dom.window.confirm("Submit this reviewed memo to committee?")) save("submit", SubmissionRequest(deal.revision, choices(select.selectedIndex), rationale.value))
      }
      send.className = "primary"
    }
    dom.window.requestAnimationFrame(_ => main.scrollTop = initialScroll)
    main.querySelectorAll("a[href^='#']").foreach { node =>
      val a = node.asInstanceOf[dom.html.Anchor]
      a.onclick = e => { e.preventDefault(); val target = a.getAttribute("href").drop(1); Option(main.querySelector("[id='" + target.replace("'", "") + "']")).foreach(_.scrollIntoView()) }
    }
  }
  private def editor(section: MemoReviewSection, bindings: Map[String, String], parent: dom.Element): Unit = {
    val panel = element("div", "", parent); panel.className = "inline-editor"
    element("p", "Edit the narrative. Shared values stay linked to the financial record wherever they appear. Save updates the memo and resets review completion.", panel)
    val area = element("div", "", panel)
    area.className = "prose-editor"
    area.contentEditable = "true"
    area.setAttribute("role", "textbox"); area.setAttribute("aria-label", section.title + " narrative"); area.setAttribute("aria-multiline", "true")
    val pattern = "\\{\\{([^}]+)\\}\\}".r
    def populate(text: String): Unit = {
      area.innerHTML = ""
      var offset = 0
      pattern.findAllMatchIn(text).foreach { m =>
        area.appendChild(dom.document.createTextNode(text.substring(offset, m.start)))
        val chip = element("span", bindings.getOrElse(m.group(1), m.group(1)), area)
        chip.setAttribute("data-binding", m.group(1)); chip.contentEditable = "false"; chip.className = "shared-value"; chip.title = m.group(1).replace('.', ' ')
        offset = m.end
      }
      area.appendChild(dom.document.createTextNode(text.substring(offset)))
    }
    populate(section.text)
    area.oninput = _ => dirty = true
    area.onpaste = event => {
      event.preventDefault()
      val text = event.asInstanceOf[dom.ClipboardEvent].clipboardData.getData("text/plain")
      dom.document.execCommand("insertText", false, text)
    }
    val values = element("select", "", panel).asInstanceOf[dom.html.Select]
    values.setAttribute("aria-label", "Insert shared value")
    element("option", "Insert shared value", values)
    bindings.toList.filterNot(_._1 == "narrative." + section.title).sortBy(_._1).foreach { (key, value) =>
      val option = element("option", key.replace('.', ' ') + ": " + value.take(70), values).asInstanceOf[dom.html.Option]
      option.value = key
    }
    values.onchange = _ => if (bindings.contains(values.value)) {
      val chip = element("span", bindings(values.value), area)
      chip.setAttribute("data-binding", values.value); chip.contentEditable = "false"; chip.className = "shared-value"
      area.appendChild(dom.document.createTextNode(" ")); dirty = true; values.selectedIndex = 0
    }
    def readArea(): String = {
      val clone = area.cloneNode(true).asInstanceOf[dom.html.Element]
      clone.querySelectorAll("[data-binding]").foreach { node =>
        val token = "{{" + node.asInstanceOf[dom.Element].getAttribute("data-binding") + "}}"
        node.parentNode.replaceChild(dom.document.createTextNode(token), node)
      }
      // innerText needs a rendered element to preserve paragraph breaks.
      clone.style.display = "block"; clone.style.position = "fixed"; clone.style.left = "-10000px"
      dom.document.body.appendChild(clone)
      val text = clone.innerText
      clone.remove()
      text
    }
    if (section.title.contains("Risk Factors")) {
      val ordering = element("details", "", panel)
      element("summary", "Reorder risk factors", ordering)
      var risks = section.text.split("\\n\\s*\\n").filter(_.trim.nonEmpty).toVector
      val list = element("div", "", ordering)
      var dragging = -1
      def reorder(from: Int, to: Int): Unit = {
        if (from >= 0 && to >= 0 && from < risks.size && to < risks.size && from != to) {
          val moved = risks(from)
          val removed = risks.patch(from, Nil, 1)
          risks = removed.patch(to, Vector(moved), 0)
          populate(risks.mkString("\n\n"))
          dirty = true
          rows()
        }
      }
      def rows(): Unit = {
        list.innerHTML = ""
        risks.zipWithIndex.foreach { (risk, index) =>
          val row = element("div", "", list)
          row.draggable = true
          row.ondragstart = _ => dragging = index
          row.ondragover = e => e.preventDefault()
          row.ondrop = e => { e.preventDefault(); reorder(dragging, index) }
          element("p", s"${index + 1}. ${risk.take(120)}", row)
          button(s"Move risk ${index + 1} up", row) { reorder(index, index - 1) }.disabled = index == 0
          button(s"Move risk ${index + 1} down", row) { reorder(index, index + 1) }.disabled = index == risks.size - 1
        }
      }
      ordering.addEventListener("toggle", (_: dom.Event) => if (ordering.hasAttribute("open")) {
        risks = readArea().split("\\n\\s*\\n").filter(_.trim.nonEmpty).toVector
        rows()
      })
      rows()
    }
    val reason = labelInput("Reason for narrative change", panel)
    button("Save narrative", panel) {
      save("narratives", NarrativeRequest(deal.revision, section.title, readArea(), reason.value))
    }.className = "primary"
    button("Cancel edit", panel) { panel.remove(); dirty = false }
    area.focus()
  }
  private def financialEditor(parent: dom.Element): Unit = {
    val details = element("details", "", parent)
    element("summary", "Edit source financial values", details)
    element("p", "Correct the source value here. Every dependent table and shared narrative value is recalculated. Amounts are USD millions.", details)
    val select = element("select", "", details).asInstanceOf[dom.html.Select]
    select.setAttribute("aria-label", "Financial value to correct")
    deal.grid.values.foreach(v => element("option", s"${v.item} · ${v.period.label}: ${v.value}", select))
    val amount = labelInput("Corrected amount (USD millions)", details)
    val reason = labelInput("Reason for financial correction", details)
    val source = element("p", "", details)
    def selected(): Unit = deal.grid.values.lift(select.selectedIndex).foreach { cell => amount.value = cell.value.toString; source.textContent = cell.source.describe; amount.title = cell.source.describe }
    select.onchange = _ => selected()
    selected()
    button("Save financial correction", details) {
      for (cell <- deal.grid.values.lift(select.selectedIndex)) {
        Try(BigDecimal(amount.value)).toOption match {
          case Some(value) => save("corrections", DealCorrectionRequest(deal.revision, GridCorrection(cell.item, cell.period, cell.value, value, cell.source, reason.value, "", "")))
          case None => dom.window.alert("Enter a valid amount.")
        }
      }
    }
  }
  element("p", "Loading memo...")
  ApiClient.memoReview(deal.id) {
    case Right(document) => render(document)
    case Left(error) => root.textContent = error
  }
}
