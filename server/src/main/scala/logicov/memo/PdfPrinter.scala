package logicov.memo

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink

import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*

class PdfPrinter(chrome: String) {
  def print(html: Path, pdf: Path): Path = {
    val log = Files.createTempFile("logicov-print-", ".log")
    try {
      val process = new ProcessBuilder(chrome, "--headless", "--disable-gpu", "--no-pdf-header-footer",
        s"--print-to-pdf=${pdf.toAbsolutePath}", html.toAbsolutePath.toUri.toString)
        .redirectErrorStream(true).redirectOutput(log.toFile).start()
      if (!process.waitFor(120, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        process.waitFor(10, TimeUnit.SECONDS)
        throw new RuntimeException("PDF printing exceeded two minutes")
      }
      if (process.exitValue() != 0 || !Files.exists(pdf)) {
        throw new RuntimeException(s"Chrome could not print $html: ${Files.readString(log).take(4000)}")
      }
    } finally Files.deleteIfExists(log)
    // Chromium resolves local HTML links to absolute file URLs. Keep the review bundle portable.
    val document = Loader.loadPDF(pdf.toFile)
    val portable = Files.createTempFile(pdf.getParent, "portable-", ".pdf")
    try {
      val prefix = html.toAbsolutePath.getParent.toUri.toString
      document.getPages.iterator().asScala.foreach { page =>
        page.getAnnotations.asScala.collect { case link: PDAnnotationLink => link }.foreach { link =>
          link.getAction match {
            case uri: PDActionURI if Option(uri.getURI).exists(_.startsWith(prefix)) =>
              uri.setURI(uri.getURI.stripPrefix(prefix))
            case _ => ()
          }
        }
      }
      document.save(portable.toFile)
    } finally document.close()
    Files.move(portable, pdf, StandardCopyOption.REPLACE_EXISTING)
    pdf
  }
}
