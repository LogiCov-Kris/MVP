package logicov.ingest

import org.apache.poi.ss.usermodel.{Cell, CellType, FormulaEvaluator, WorkbookFactory}
import org.apache.poi.openxml4j.util.ZipSecureFile

import java.io.ByteArrayInputStream
import scala.jdk.CollectionConverters.*
import scala.util.Try

object WorkbookStatements {
  def read(bytes: Array[Byte]): List[StatementRow] = {
    ZipSecureFile.setMaxEntrySize(20L * 1024 * 1024)
    val workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))
    try {
      require(workbook.getNumberOfSheets <= 20, "At most 20 sheets are supported")
      val evaluator = workbook.getCreationHelper.createFormulaEvaluator()
      val rows = workbook.iterator().asScala.flatMap { sheet =>
        require(sheet.getLastRowNum < 2000, "At most 2000 rows per sheet are supported")
        sheet.iterator().asScala.map { row =>
          require(row.getLastCellNum <= 40, "At most 40 columns are supported")
          StatementRow((0 until row.getLastCellNum.toInt).map { index =>
            Option(row.getCell(index)).fold(StatementCell("", s"${sheet.getSheetName}!row ${row.getRowNum + 1}, column ${index + 1}")) {
              cell => readCell(cell, evaluator)
            }
          }.toList)
        }
      }.take(2001).toList
      require(rows.size <= 2000, "At most 2000 total rows are supported")
      rows
    } finally workbook.close()
  }

  private def readCell(cell: Cell, evaluator: FormulaEvaluator): StatementCell = {
    val location = s"${cell.getSheet.getSheetName}!${cell.getAddress.formatAsString()}"
    cell.getCellType match {
      case CellType.NUMERIC => StatementCell(BigDecimal.decimal(cell.getNumericCellValue).bigDecimal.toPlainString, location)
      case CellType.STRING => StatementCell(cell.getStringCellValue, location)
      case CellType.BLANK => StatementCell("", location)
      case CellType.FORMULA =>
        val formula = cell.getCellFormula
        // Formula evaluation must not fall back to stale cached workbook values.
        Try(evaluator.evaluate(cell)).toEither match {
          case Right(value) if value != null && value.getCellType == CellType.NUMERIC =>
            StatementCell(BigDecimal.decimal(value.getNumberValue).bigDecimal.toPlainString, s"$location (formula: $formula)")
          case _ => StatementCell(formula, location, Some("Formula could not be evaluated to a number; replace it with a verified value"))
        }
      case _ => StatementCell(cell.toString, location, Some("Non-numeric or error cell"))
    }
  }
}
