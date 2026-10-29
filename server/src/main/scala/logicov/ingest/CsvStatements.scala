package logicov.ingest

import org.apache.commons.csv.CSVFormat

import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import scala.jdk.CollectionConverters.*

object CsvStatements {
  def read(bytes: Array[Byte]): List[StatementRow] = {
    val decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
    val text = decoder.decode(ByteBuffer.wrap(bytes)).toString.stripPrefix("\uFEFF")
    val parser = CSVFormat.RFC4180.parse(new StringReader(text))
    try {
      val rows = parser.iterator().asScala.take(2001).map { record =>
        require(record.size() <= 40, "At most 40 columns are supported")
        StatementRow(record.iterator().asScala.zipWithIndex.map { (value, column) =>
          StatementCell(value, s"record ${record.getRecordNumber}, column ${column + 1}")
        }.toList)
      }.toList
      require(rows.size <= 2000, "At most 2000 rows are supported")
      rows
    } finally parser.close()
  }
}
