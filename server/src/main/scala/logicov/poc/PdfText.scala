package logicov.poc

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.{PDFTextStripper, TextPosition}

import java.io.File
import java.util.List as JList
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.*

case class PdfLine(page: Int, x: Double, y: Double, fontSize: Double, text: String)

object PdfText {
  def lines(file: File): List[PdfLine] = {
    val document = Loader.loadPDF(file)
    try {
      val collected = ListBuffer.empty[PdfLine]
      val stripper = new PDFTextStripper {
        override def writeString(text: String, positions: JList[TextPosition]): Unit = {
          val list = positions.asScala
          if (list.nonEmpty && text.trim.nonEmpty) {
            val first = list.head
            collected += PdfLine(
              page = getCurrentPageNo,
              x = first.getXDirAdj.toDouble,
              y = first.getYDirAdj.toDouble,
              fontSize = first.getFontSizeInPt.toDouble,
              text = text
            )
          }
        }
      }
      stripper.setSortByPosition(true)
      stripper.getText(document)
      collected.toList
    } finally {
      document.close()
    }
  }
}
