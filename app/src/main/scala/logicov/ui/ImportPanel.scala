package logicov.ui

import everyui.Color
import everyui.component.{Column, Container, Row, Select, TextInput, TextView}
import everyui.component.types.{Semantics, TextAlign, WhiteSpace}
import everyui.draw.{VectorArt, VectorIcon}
import everyui.layout.{HorizontalLayout, VerticalLayout}
import everyui.table.{DataColumn, DataTable, GridColumn, VirtualTable}
import everyui.theme.Theme
import fabric.io.{JsonFormatter, JsonParser}
import fabric.rw.*
import logicov.ingest.{AmountUnit, ImportColumn, ImportOptions, ImportRequest, ImportResult}
import logicov.source.Source
import logicov.workspace.{CreateDealRequest, WorkspaceDeal}
import org.scalajs.dom
import reactify.*

import java.time.LocalDate
import scala.scalajs.js
import scala.util.Try

class ImportPanel(width: () => Double, saved: WorkspaceDeal => Unit = _ => ()) extends Container {
  layout @= Some(VerticalLayout(24.0))
  size.width := width()
  private val message = Var("")
  private val busy = Var(false)
  private val fileName = Var("Drop your financial statement here")
  private val fileHint = Var("Excel, PDF, CSV or XBRL · up to 5 MB")
  private val fileSelected = Var(false)
  private var selected: Option[dom.File] = None
  private val options = Var(Option.empty[ImportOptions])
  private var exported = true
  private val advancedOpen = Var(false)
  private val advancedMessage = Var("")
  private val body = new Container
  body.layout @= Some(VerticalLayout(20.0))
  private val innerWidth = () => width() - 48.0
  private val formWidth = () => if (width() >= 850.0) width() - 300.0 else width()
  private val fieldWidth = () => formWidth() - 48.0
  private val entity = new TextInput("Borrower legal name")
  private val years = new TextInput("Statement years, left to right")
  entity.fieldName @= "borrower"
  years.fieldName @= "years"
  years.value @= "2025"
  entity.size.width := fieldWidth()
  private val settingWidth = () => if (fieldWidth() >= 500.0) (fieldWidth() - 16.0) / 2.0 else fieldWidth()
  years.size.width := settingWidth()
  private val units = new Select(List("USD millions", "USD thousands", "USD dollars"))
  units.size.width := settingWidth()
  units.size.height @= 46.0
  units.node.el.querySelector("select").setAttribute("aria-label", "Statement units")
  private val status = TextView.bound(message(), 13.0, Theme.danger())
  status.size.width := fieldWidth()
  status.whiteSpace @= WhiteSpace.Normal
  status.node.el.setAttribute("role", "status")
  private val title = WorkspaceStyle.text("New Credit File", 30.0, bold = true)
  title.semantics @= Semantics.heading(1)
  private val subtitle = WorkspaceStyle.paragraph("Turn source financials into a clear, traceable Golden Grid.", width, 15.0)
  private val steps = WorkspaceStyle.text("01  Upload statement     /     02  Review grid", 12.0, muted = true)
  steps.size.width := width()
  steps.whiteSpace @= WhiteSpace.Normal
  children @= Vector(Column(10.0)(title, subtitle, steps), body)

  private val choose = WorkspaceStyle.button("Choose statement", () => pick(".pdf,.csv,.xls,.xlsx,.xbrl,.xml")(selectFile))
  private val preview = WorkspaceStyle.button("Ingest & Analyze", () => run(), primary = true)
  preview.enabled := !busy() && fileSelected() && entity.value().trim.nonEmpty
  preview.label := (if (busy()) "Reading statement..." else "Ingest & Analyze")
  choose.enabled := !busy()
  entity.readOnly := busy() || options().nonEmpty
  years.readOnly := busy() || options().nonEmpty

  private val dropZone = new Container
  dropZone.size.width := fieldWidth()
  dropZone.backgroundColor := Theme.control()
  dropZone.borderWidth @= 0.0
  dropZone.borderColor := Theme.border()
  dropZone.radius @= 9.0
  dropZone.node.el.style.border = "1px dashed #b8cbcf"
  dropZone.semantics @= Semantics.region("Statement upload")
  private val uploadIcon = VectorIcon(VectorArt.path("M11 16h2V7.8l3.1 3.1 1.4-1.4L12 4 6.5 9.5l1.4 1.4L11 7.8V16zM5 17H3v4h18v-4h-2v2H5v-2z"), 32.0)
  uploadIcon.color @= WorkspaceStyle.Teal
  private val fileTitle = TextView.bound(fileName(), 14.0, Theme.text())
  fileTitle.size.width := fieldWidth() - 32.0
  fileTitle.whiteSpace @= WhiteSpace.Normal
  fileTitle.align @= TextAlign.Center
  private val fileCaption = TextView.bound(fileHint(), 12.0, Theme.muted())
  fileCaption.size.width := fieldWidth() - 32.0
  fileCaption.whiteSpace @= WhiteSpace.Normal
  fileCaption.align @= TextAlign.Center
  private val uploadContent = Column(10.0, center = true)(uploadIcon, fileTitle, fileCaption, choose)
  uploadContent.position.center := dropZone.size.center()
  uploadContent.position.middle := dropZone.size.middle()
  dropZone.children += uploadContent
  dropZone.size.height := math.max(168.0, uploadContent.size.height() + 28.0)
  dropZone.node.el.ondragover = (event: dom.DragEvent) => { event.preventDefault(); dropZone.backgroundColor @= WorkspaceStyle.Soft }
  dropZone.node.el.ondragleave = (_: dom.DragEvent) => dropZone.backgroundColor := Theme.control()
  dropZone.node.el.ondrop = (event: dom.DragEvent) => {
    event.preventDefault()
    dropZone.backgroundColor := Theme.control()
    if (!busy() && event.dataTransfer.files.length == 1) selectFile(event.dataTransfer.files(0))
    else if (!busy()) message @= "Choose one statement at a time."
  }

  private val advanced = Column(12.0)()
  private val advancedStatus = TextView.bound(advancedMessage(), 12.0, Theme.muted())
  advancedStatus.size.width := fieldWidth()
  advancedStatus.whiteSpace @= WhiteSpace.Normal
  private val loadSettings = WorkspaceStyle.button("Load settings file", () => pick(".json")(loadOptions))
  private val resetSettings = WorkspaceStyle.button("Use form settings", () => {
    options @= None
    advancedMessage @= "Using the borrower, years and units above."
  })
  private val settingsToggle = WorkspaceStyle.button("Advanced options", () => {
    advancedOpen @= !advancedOpen()
    advanced.children @= (if (advancedOpen()) Vector(
      WorkspaceStyle.paragraph("For custom periods, page selection, label mappings or sign changes, load an import settings file.", fieldWidth, 12.0),
      loadSettings, template, resetSettings, advancedStatus) else Vector.empty)
  })
  settingsToggle.enabled := !busy()
  settingsToggle.label := (if (options().nonEmpty) "Advanced options · loaded" else "Advanced options")
  loadSettings.enabled := !busy()
  resetSettings.enabled := !busy()
  Val(busy() || options().nonEmpty).attach { value =>
    units.node.el.querySelector("select").asInstanceOf[dom.html.Select].disabled = value
  }
  private val template = WorkspaceStyle.button("Download settings template", () => save("import-options.json",
    """{"entity":"Example Company","unit":"Millions","columns":[{"column":2,"label":"FY2025","start":"2025-01-01","end":"2025-12-31"}],"mappings":{},"pdfPages":[],"negate":[]}"""))
  private val periodFields = new Container
  private val unitsGroup = Column(8.0)(WorkspaceStyle.text("Statement units", 12.0, bold = true), units)
  unitsGroup.position.left := (if (fieldWidth() >= 500.0) settingWidth() + 16.0 else 0.0)
  unitsGroup.position.top := (if (fieldWidth() >= 500.0) 0.0 else years.size.height() + 16.0)
  periodFields.size.width := fieldWidth()
  periodFields.size.height := (if (fieldWidth() >= 500.0) math.max(years.size.height(), unitsGroup.size.height()) else years.size.height() + 16.0 + unitsGroup.size.height())
  periodFields.children ++= Vector(years, unitsGroup)
  private val formActions = new Container
  formActions.size.width := fieldWidth()
  formActions.size.height := (if (fieldWidth() < 420.0) 96.0 else 42.0)
  preview.position.left := (if (fieldWidth() < 420.0) 0.0 else fieldWidth() - preview.size.width())
  preview.position.top := (if (fieldWidth() < 420.0) 54.0 else 0.0)
  formActions.children ++= Vector(settingsToggle, preview)
  private val publicPath = WorkspaceStyle.button("Public entity · Coming soon", () => ())
  publicPath.enabled @= false
  private val paths = Row(12.0)(WorkspaceStyle.button("Private entity", () => (), primary = true), publicPath)
  paths.layout := Some(if (fieldWidth() < 420.0) VerticalLayout(10.0) else HorizontalLayout(12.0))
  private val form = WorkspaceStyle.card(formWidth, paths,
    WorkspaceStyle.text("Private entity", 20.0, bold = true),
    WorkspaceStyle.paragraph("Enter the borrower’s legal name and upload financial statements. Public entity onboarding is coming soon.", fieldWidth),
    dropZone, entity, periodFields,
    WorkspaceStyle.paragraph("For example: 2025, 2024, 2023. Match the order of columns in your statement.", fieldWidth, 12.0),
    advanced, status, formActions)
  private val guide = WorkspaceStyle.card(() => 276.0,
    WorkspaceStyle.text("A clear path to review", 16.0, bold = true),
    WorkspaceStyle.text("1  Bring your source", 13.0, bold = true),
    WorkspaceStyle.paragraph("Use a text-based PDF or a spreadsheet with line items in the first column.", () => 228.0, 13.0),
    WorkspaceStyle.text("2  Check the extraction", 13.0, bold = true),
    WorkspaceStyle.paragraph("Review the grid, inspect source references and resolve any flagged rows.", () => 228.0, 13.0),
    WorkspaceStyle.text("3  Keep your results", 13.0, bold = true),
    WorkspaceStyle.paragraph("Your source and extracted values are saved together. Correct values before continuing to credit review.", () => 228.0, 13.0))
  guide.position.left := formWidth() + 24.0
  guide.visible := width() >= 850.0
  private val formLayout = new Container
  formLayout.size.width := width()
  formLayout.size.height := math.max(form.size.height(), if (guide.visible()) guide.size.height() else 0.0)
  formLayout.children ++= Vector(form, guide)
  body.children @= Vector(formLayout)

  def processing: Boolean = busy()

  def canLeave: Boolean = !busy() && (exported || dom.window.confirm("Leave without saving this credit file?"))

  private def selectFile(file: dom.File): Unit = {
    if (file.size > 5 * 1024 * 1024) rejectFile("This file exceeds 5 MB. Choose a smaller statement.")
    else if (!Set("pdf", "csv", "xls", "xlsx", "xbrl", "xml").contains(file.name.toLowerCase.split('.').last)) rejectFile("Choose a Excel, PDF, CSV or XBRL statement.")
    else {
      exported = false
      selected = Some(file)
      fileSelected @= true
      fileName @= file.name
      fileHint @= f"${file.size / 1024.0}%.0f KB · Ready to review"
      message @= ""
    }
  }

  private def rejectFile(error: String): Unit = {
    selected = None
    fileSelected @= false
    fileName @= "Drop your financial statement here"
    fileHint @= "Excel, PDF, CSV or XBRL · up to 5 MB"
    message @= error
  }

  private def pick(accept: String)(done: dom.File => Unit): Unit = {
    val input = dom.document.createElement("input").asInstanceOf[dom.html.Input]
    input.`type` = "file"
    input.accept = accept
    input.onchange = _ => if (input.files.length > 0) {
      val file = input.files(0)
      if (file.size > 5 * 1024 * 1024) message @= "File exceeds 5 MB."
      else done(file)
    }
    input.click()
  }

  private def loadOptions(file: dom.File): Unit = {
    val reader = new dom.FileReader()
    reader.onload = _ => Try {
      val value = JsonParser(reader.result.toString).as[ImportOptions]
      value.validate()
      value
    }.toEither match {
      case Right(value) =>
        options @= Some(value)
        entity.value @= value.entity
        years.value @= value.columns.map(_.end.getYear.toString).mkString(", ")
        units.selected @= List(AmountUnit.Millions, AmountUnit.Thousands, AmountUnit.Dollars).indexOf(value.unit)
        advancedMessage @= s"Using ${file.name}: ${value.entity}, ${value.unit}, ${value.columns.map(_.label).mkString(", ")}. These settings replace the form values."
      case Left(_) => options @= None; advancedMessage @= "This settings file could not be read. Check its periods, units and mappings."
    }
    reader.onerror = _ => advancedMessage @= "Could not read the settings file."
    reader.readAsText(file)
  }

  private def run(): Unit = {
    val config = options().orElse(Try {
      val columns = years.value().split(",").toList.zipWithIndex.map { (text, index) =>
        val year = text.trim.toInt
        require(year >= 1900 && year <= 2100)
        ImportColumn(index + 2, s"FY$year", LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31))
      }
      val unit = List(AmountUnit.Millions, AmountUnit.Thousands, AmountUnit.Dollars)(units.selected())
      val value = ImportOptions(entity.value(), unit, columns)
      value.validate()
      value
    }.toOption)
    if (selected.isEmpty) message @= "Choose a financial statement to continue."
    else if (config.isEmpty) {
      message @= "Enter a borrower name and valid statement years, such as 2025, 2024."
      if (entity.value().trim.isEmpty) entity.focus() else years.focus()
    } else for (file <- selected; settings <- config) {
      busy @= true
      message @= ""
      val reader = new dom.FileReader()
      body.children @= Vector(new ProcessingPanel(width, "Analyzing financial statements"))
      reader.onerror = _ => { busy @= false; body.children @= Vector(formLayout); message @= "Could not read the statement. Please choose it again." }
      reader.onload = _ => {
        val request = ImportRequest(file.name, reader.result.toString.split(",", 2).last, settings)
        ApiClient.create(CreateDealRequest(settings.entity, upload = Some(request))) {
        case Left(error) => busy @= false; body.children @= Vector(formLayout); message @= error
        case Right(value) => busy @= false; exported = true; saved(value)
      }
      }
      reader.readAsDataURL(file)
    }
  }

  private def save(name: String, contents: String): Unit = {
    val blob = new dom.Blob(js.Array(contents), new dom.BlobPropertyBag { `type` = "application/json" })
    val url = dom.URL.createObjectURL(blob)
    val anchor = dom.document.createElement("a").asInstanceOf[dom.html.Anchor]
    anchor.href = url
    anchor.download = name
    anchor.click()
    dom.window.setTimeout(() => dom.URL.revokeObjectURL(url), 1000)
  }
}
