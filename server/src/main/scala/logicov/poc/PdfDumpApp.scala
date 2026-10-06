package logicov.poc

import java.io.File
import java.nio.file.Files

object PdfDumpApp {
  def main(args: Array[String]): Unit = {
    val input = new File(args.headOption.getOrElse("poc/samples"))
    val output = new File(args.lift(1).getOrElse("poc/out"))
    output.mkdirs()
    val pdfs = if (input.isDirectory) {
      input.listFiles().toList.filter(_.getName.toLowerCase.endsWith(".pdf")).sortBy(_.getName)
    } else {
      List(input)
    }
    pdfs.foreach { pdf =>
      val lines = PdfText.lines(pdf)
      val text = lines.map { l =>
        f"p${l.page}%03d y=${l.y}%7.1f x=${l.x}%6.1f s=${l.fontSize}%4.1f | ${l.text}"
      }.mkString("\n")
      val target = new File(output, pdf.getName.stripSuffix(".pdf").stripSuffix(".PDF") + ".txt")
      Files.writeString(target.toPath, text)
      scribe.info(s"${pdf.getName}: ${lines.map(_.page).distinct.size} pages, ${lines.size} lines -> $target")
    }
  }
}
