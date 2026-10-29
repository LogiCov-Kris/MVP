package logicov.ingest

import logicov.grid.{GoldenGrid, GridValidation, GridValue}
import logicov.source.Source

import java.security.MessageDigest
import java.util.{HexFormat, Locale}
import scala.collection.mutable.ListBuffer

object StatementImporter {
  val MaxBytes: Int = 5 * 1024 * 1024

  def run(filename: String, bytes: Array[Byte], options: ImportOptions): ImportResult = {
    options.validate()
    require(filename.nonEmpty && filename.length <= 200 && !filename.exists(c => c == '/' || c == '\\' || c.isControl), "Invalid filename")
    require(bytes.nonEmpty && bytes.length <= MaxBytes, "Upload must contain between 1 byte and 5 MB")
    val rows = filename.toLowerCase(Locale.ROOT).split('.').last match {
      case "csv" => CsvStatements.read(bytes)
      case "xls" | "xlsx" => WorkbookStatements.read(bytes)
      case "pdf" => PdfStatements.read(bytes, options)
      case _ => throw new IllegalArgumentException("Supported formats: PDF, CSV, XLS and XLSX")
    }
    val sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    val issues = ListBuffer.empty[ImportIssue]
    val values = ListBuffer.empty[GridValue]
    rows.foreach { row =>
      row.cells.headOption.foreach { first =>
        val label = first.text.trim
        val header = Set("lineitem", "account", "description").contains(StatementLabels.normalize(label))
        if (header) {
          options.columns.foreach { column =>
            val actual = row.cells.lift(column.column - 1).map(_.text.trim).getOrElse("")
            if (actual != column.label && actual != column.end.getYear.toString && actual != column.end.toString) {
              issues += ImportIssue(ImportIssueKind.Header, first.location, s"Column ${column.column}: header '$actual' does not match ${column.label}; confirm the selected period")
            }
          }
        } else if (row.cells.exists(_.text.trim.nonEmpty)) {
          StatementLabels.find(label, options.mappings) match {
            case None => issues += ImportIssue(ImportIssueKind.UnknownLabel, first.location, s"Unmapped row: ${label.take(300)}")
            case Some(item) => options.columns.foreach { column =>
              val cell = row.cells.lift(column.column - 1).getOrElse(StatementCell("", s"${first.location}, column ${column.column}"))
              val amount = StatementAmounts.read(cell.text)
              if (cell.error.nonEmpty) issues += ImportIssue(
                if (filename.toLowerCase(Locale.ROOT).endsWith(".pdf")) ImportIssueKind.InvalidValue else ImportIssueKind.Formula,
                cell.location, cell.error.getOrElse("Invalid cell"))
              else amount match {
                case Some(value) => values += GridValue(item, column.period(item.flow), (if (options.negate.contains(item)) -value else value) / options.unit.divisor,
                  Source.Upload(filename, sha, cell.location, s"$label: ${cell.text.take(300)}; units: ${options.unit}; sign reversed: ${options.negate.contains(item)}"))
                case None => issues += ImportIssue(if (cell.text.trim.isEmpty || Set("-", "–", "—").contains(cell.text.trim)) ImportIssueKind.MissingValue else ImportIssueKind.InvalidValue,
                  cell.location, s"$label: '${cell.text.take(100)}' requires review; no zero was substituted")
              }
            }
          }
        }
      }
    }
    val grid = GoldenGrid(options.entity.trim, values.toList)
    if (values.isEmpty) issues += ImportIssue(ImportIssueKind.Empty, filename, "No financial values were extracted")
    ImportResult(grid, filename, sha, issues.toList ++ GridValidation.check(grid), rows.size)
  }
}
