package logicov.ingest

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper

object PdfStatements {
  private val Amount = """(?:\$\s*)?\(?-?[0-9][0-9,]*(?:\.[0-9]+)?\)?|[-–—]""".r

  def read(bytes: Array[Byte], options: ImportOptions): List[StatementRow] = {
    val document = Loader.loadPDF(bytes)
    try {
      require(!document.isEncrypted, "Encrypted PDFs are not supported")
      require(document.getNumberOfPages <= 100, "At most 100 PDF pages are supported")
      val pages = if (options.pdfPages.isEmpty) (1 to document.getNumberOfPages).toList else options.pdfPages.distinct.sorted
      require(pages.forall(_ <= document.getNumberOfPages), "Selected PDF page does not exist")
      val stripper = new PDFTextStripper()
      stripper.setSortByPosition(true)
      val rows = pages.flatMap { page =>
        stripper.setStartPage(page)
        stripper.setEndPage(page)
        stripper.getText(document).linesIterator.zipWithIndex.filter(_._1.trim.nonEmpty).map { (line, index) =>
          val text = line.trim
          require(text.length <= 4000, "PDF line exceeds supported length")
          val matches = Amount.findAllMatchIn(text).toList
          var end = text.length
          val trailing = matches.reverse.takeWhile { m =>
            val adjacent = text.substring(m.end, end).trim.isEmpty
            if (adjacent) end = m.start
            adjacent
          }.reverse
          val label = trailing.headOption.fold(text)(m => text.substring(0, m.start).trim)
          val location = s"page $page, line ${index + 1}"
          StatementRow(StatementCell(label, location) :: trailing.zipWithIndex.map { (m, col) =>
            StatementCell(m.matched, s"$location, value ${col + 1}",
              Option.when(trailing.size != options.columns.map(_.column).max - 1)(
                "PDF value-column count differs from settings; review footnotes and column order"))
          })
        }.toList
      }
      require(rows.nonEmpty, "No PDF text found. Scanned documents require OCR, which is not supported")
      require(rows.size <= 2000, "At most 2000 text rows are supported; select statement pages")
      rows
    } finally document.close()
  }
}
