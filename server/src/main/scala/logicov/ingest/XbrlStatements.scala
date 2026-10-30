package logicov.ingest

import logicov.edgar.XbrlInstance
import logicov.grid.{GoldenGrid, GridValidation, GridValue, LineItem}
import logicov.source.Source

import java.security.MessageDigest
import java.util.HexFormat

object XbrlStatements {
  def read(filename: String, bytes: Array[Byte], options: ImportOptions): ImportResult = {
    val instance = XbrlInstance.parse("Uploaded statement", filename, bytes, None, standardOnly = true)
    val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    val issues = List.newBuilder[ImportIssue]
    val values = List.newBuilder[GridValue]
    for (column <- options.columns; item <- LineItem.values) {
      val start = if (item.flow) Some(column.start) else None
      val candidates = instance.facts.filter(f => f.start == start && f.end == column.end && f.dimensions.isEmpty && f.isUsd)
      def amount(concept: String): Option[BigDecimal] = {
        val amounts = candidates.filter(_.concept == concept).map(_.amount).distinct
        if (amounts.size > 1) {
          issues += ImportIssue(ImportIssueKind.Duplicate, s"$concept / ${column.label}", "Conflicting undimensioned facts require review")
          None
        } else amounts.headOption
      }
      val resolved = item.tags.iterator.flatMap { tag =>
        val components = tag.less.map(amount) ++ tag.plus.map(amount)
        amount(tag.name).filter(_ => components.forall(_.nonEmpty)).map { base =>
          val result = base - tag.less.flatMap(amount).sum + tag.plus.flatMap(amount).sum
          tag.name -> (if (tag.negate) -result else result)
        }
      }.take(1).toList.headOption
      resolved.foreach { (concept, amount) =>
        values += GridValue(item, column.period(item.flow), (if (options.negate.contains(item)) -amount else amount) / 1000000,
          Source.Upload(filename, hash, s"$concept; ${column.label}; undimensioned USD context", s"$concept: $amount USD; ${column.start} to ${column.end}"))
      }
    }
    val grid = GoldenGrid(options.entity.trim, values.result())
    if (grid.values.isEmpty) issues += ImportIssue(ImportIssueKind.Empty, filename, "No supported USD facts matched the selected periods. Inline XBRL and custom taxonomies require separate mapping.")
    ImportResult(grid, filename, hash, issues.result() ++ GridValidation.check(grid), instance.facts.size)
  }
}
