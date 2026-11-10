package logicov.tools

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import fabric.io.JsonFormatter
import fabric.rw.*
import java.net.InetSocketAddress
import java.nio.file.{Files, Path, StandardCopyOption}
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import scala.util.matching.Regex

object MailCapture {
  def fields(contentType: String, bytes: Array[Byte]): Map[String, String] = {
    require(bytes.length <= 65536, "Message exceeds capture limit")
    val boundary = "(?i)boundary=(?:\"([^\"]+)\"|([^;\\s]+))".r.findFirstMatchIn(contentType)
      .map(m => Option(m.group(1)).getOrElse(m.group(2))).getOrElse(throw new IllegalArgumentException("Multipart boundary required"))
    require(boundary.nonEmpty && boundary.length <= 200 && !boundary.exists(c => c == '\r' || c == '\n'), "Invalid boundary")
    val raw = new String(bytes, "UTF-8")
    require(raw.endsWith(s"--$boundary--\r\n") || raw.endsWith(s"--$boundary--"), "Incomplete multipart request")
    raw.split(Regex.quote("--" + boundary)).toList.drop(1).filterNot(_.startsWith("--")).map { part =>
      val pieces = part.stripPrefix("\r\n").split("\r\n\r\n", 2)
      require(pieces.length == 2, "Malformed multipart field")
      val name = "(?im)^Content-Disposition:.*?\\bname=\"([^\"]+)\"".r.findFirstMatchIn(pieces(0)).map(_.group(1))
        .getOrElse(throw new IllegalArgumentException("Missing field name"))
      name -> pieces(1).stripSuffix("\r\n")
    }.toMap
  }
  def run(output: Path): Unit = {
    Files.createDirectories(output.toAbsolutePath.getParent)
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 18424), 0)
    server.createContext("/ready", exchange => respond(exchange, 200, "{}"))
    server.createContext("/emails", exchange => {
      try {
        require(exchange.getRequestMethod == "POST" && exchange.getRequestURI.getPath == "/emails", "Unsupported request")
        val values = fields(Option(exchange.getRequestHeaders.getFirst("Content-Type")).getOrElse(""), exchange.getRequestBody.readNBytes(65537))
        val temp = Files.createTempFile(output.toAbsolutePath.getParent, "message-", ".json", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        try {
          Files.writeString(temp, JsonFormatter.Compact(values.json))
          Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally Files.deleteIfExists(temp)
        respond(exchange, 200, "{\"id\":\"local-browser-test\"}")
      } catch {
        case error: IllegalArgumentException => respond(exchange, 400, "{\"error\":\"Invalid capture request\"}")
        case error: Exception => System.err.println(s"Capture failed: ${error.getClass.getSimpleName}"); respond(exchange, 500, "{}")
      }
    })
    Runtime.getRuntime.addShutdownHook(new Thread(() => server.stop(0)))
    server.start()
    println("Mail capture ready on 127.0.0.1:18424")
    new CountDownLatch(1).await()
  }
  private def respond(exchange: HttpExchange, status: Int, body: String): Unit = {
    val bytes = body.getBytes("UTF-8")
    exchange.getResponseHeaders.set("Content-Type", "application/json")
    exchange.sendResponseHeaders(status, bytes.length)
    try exchange.getResponseBody.write(bytes) finally exchange.close()
  }
}
