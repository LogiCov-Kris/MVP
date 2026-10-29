package logicov.ingest

import fabric.rw.*
import logicov.grid.LineItem

case class ImportOptions(entity: String,
                         unit: AmountUnit,
                         columns: List[ImportColumn],
                         mappings: Map[String, LineItem] = Map.empty,
                         pdfPages: List[Int] = Nil,
                         negate: List[LineItem] = Nil) derives RW {
  def validate(): Unit = {
    require(entity.trim.nonEmpty && entity.length <= 200, "Entity name required (up to 200 characters)")
    require(columns.nonEmpty && columns.size <= 10, "Specify 1 to 10 period columns")
    require(columns.map(_.column).distinct.size == columns.size, "Duplicate columns are not allowed")
    require(columns.forall(c => c.column >= 2 && c.column <= 40 && !c.start.isAfter(c.end) && c.label.nonEmpty), "Invalid column or period")
    require(columns.map(c => (c.start, c.end)).distinct.size == columns.size, "Duplicate periods are not allowed")
    require(mappings.size <= 200 && mappings.keys.forall(k => k.nonEmpty && k.length <= 300), "Invalid label mappings")
    val normalized = mappings.keys.map(_.toLowerCase.replaceAll("[^a-z0-9]", "")).toList
    require(normalized.distinct.size == normalized.size, "Conflicting normalized label mappings")
    require(pdfPages.forall(_ > 0), "PDF pages are numbered from 1")
  }
}
