package logicov.ingest

import fabric.io.JsonParser
import fabric.rw.*
import logicov.grid.{LineItem, Period}
import logicov.source.Source
import org.apache.pdfbox.pdmodel.{PDDocument, PDPage, PDPageContentStream}
import org.apache.pdfbox.pdmodel.font.{PDType1Font, Standard14Fonts}
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.ss.usermodel.Workbook
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.time.LocalDate

class StatementImporterSpec extends AnyFlatSpec with Matchers {
  private val options = ImportOptions("Example", AmountUnit.Thousands,
    List(ImportColumn(2, "FY2025", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31))))
  private def csv(text: String, config: ImportOptions = options): ImportResult =
    StatementImporter.run("statement.csv", text.getBytes(StandardCharsets.UTF_8), config)

  "CSV ingestion" should "preserve quoted amounts, negative signs, units and exact locations" in {
    val result = csv("Account,2025\r\nNet sales,\"1,200\"\r\nOperatingIncome,(50)\r\nCash,0\r\n")
    result.issues should be(empty)
    result.grid(LineItem.Revenue, Period.fiscalYear(2025)) should be(BigDecimal("1.2"))
    result.grid(LineItem.OperatingIncome, Period.fiscalYear(2025)) should be(BigDecimal("-0.05"))
    result.grid(LineItem.Cash, Period.yearEnd(2025)) should be(BigDecimal(0))
    result.grid.values.head.source match {
      case Source.Upload("statement.csv", hash, location, excerpt) =>
        hash.length should be(64)
        location should be("record 2, column 2")
        excerpt should include("1,200")
      case other => fail(s"Unexpected citation $other")
    }
  }

  it should "surface unknown rows, missing amounts, invalid amounts, duplicates and reconciliation failures" in {
    val result = csv("Account,2024\nUnmapped,5\nCash,-\nRevenue,garbage\nTotalAssets,100\nTotalLiabilities,60\nTotalEquity,30\nTotalEquity,30\n")
    result.issues.map(_.kind).toSet should contain allOf (ImportIssueKind.Header, ImportIssueKind.UnknownLabel,
      ImportIssueKind.MissingValue, ImportIssueKind.InvalidValue, ImportIssueKind.Duplicate, ImportIssueKind.Reconciliation)
    result.grid.get(LineItem.Cash, Period.yearEnd(2025)) should be(None)
  }

  it should "use explicit analyst mappings without guessing ambiguous net income" in {
    csv("Net income,20").grid.values should be(empty)
    csv("Net income,20", options.copy(mappings = Map("Net income" -> LineItem.NetIncomeConsolidated)))
      .grid.values.head.item should be(LineItem.NetIncomeConsolidated)
    intercept[Exception](csv("Revenue,\"unclosed"))
    intercept[IllegalArgumentException](StatementImporter.run("../file.csv", Array[Byte](1), options))
  }

  it should "reverse outflow signs only when explicitly configured" in {
    val result = csv("CashCapex,($1,000)", options.copy(negate = List(LineItem.CashCapex)))
    // Quoted commas are required by CSV; an unquoted amount is invalid rather than guessed.
    result.issues.map(_.kind) should contain(ImportIssueKind.InvalidValue)
    val quoted = csv("CashCapex,\"($1,000)\"", options.copy(negate = List(LineItem.CashCapex)))
    quoted.grid.values.head.value should be(BigDecimal(1))
    quoted.grid.values.head.source.describe should include("sign reversed: true")
  }

  "Import settings" should "accept the downloadable settings format" in {
    val json = """{"entity":"Example Company","unit":"Millions","columns":[{"column":2,"label":"FY2025","start":"2025-01-01","end":"2025-12-31"}],"mappings":{},"pdfPages":[]}"""
    val value = JsonParser(json).as[ImportOptions]
    value.validate()
    value.unit should be(AmountUnit.Millions)
  }

  for ((extension, create) <- List[(String, () => Workbook)]("xls" -> (() => new HSSFWorkbook()), "xlsx" -> (() => new XSSFWorkbook()))) {
    "Workbook ingestion" should s"read real $extension files and evaluate formulas with sheet/cell citations" in {
      val book = create()
      val out = new ByteArrayOutputStream()
      try {
        val sheet = book.createSheet("Financials")
        val row = sheet.createRow(0)
        row.createCell(0).setCellValue("Revenue")
        row.createCell(1).setCellFormula("1000+250")
        val bad = sheet.createRow(1)
        bad.createCell(0).setCellValue("Cash")
        bad.createCell(1).setCellFormula("1/0")
        book.write(out)
      } finally book.close()
      val result = StatementImporter.run(s"statement.$extension", out.toByteArray, options)
      result.grid.values.head.value should be(BigDecimal("1.25"))
      result.grid.values.head.source.describe should include("Financials!B1")
      result.issues.map(_.kind) should contain(ImportIssueKind.Formula)
    }
  }

  "PDF ingestion" should "retain page citations and reject scans without inventing values" in {
    val document = new PDDocument()
    val out = new ByteArrayOutputStream()
    try {
      document.addPage(new PDPage())
      document.addPage(new PDPage())
      val content = new PDPageContentStream(document, document.getPage(1))
      try {
        content.beginText()
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12)
        content.newLineAtOffset(40, 700)
        content.showText("Revenue  1200")
        content.newLineAtOffset(0, -20)
        content.showText("Cash (1)  500")
        content.endText()
      } finally content.close()
      document.save(out)
    } finally document.close()
    val result = StatementImporter.run("statement.pdf", out.toByteArray, options.copy(pdfPages = List(2)))
    result.grid.values.head.value should be(BigDecimal("1.2"))
    result.grid.values.head.source.describe should include("page 2")
    result.grid.values.size should be(1)
    result.issues.exists(_.message.contains("column count")) should be(true)
    intercept[IllegalArgumentException](StatementImporter.run("statement.pdf", out.toByteArray, options.copy(pdfPages = List(1))))
  }
}
