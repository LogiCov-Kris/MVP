package logicov.tools

import fabric.Json
import fabric.rw.*
import fabric.io.JsonFormatter
import java.net.{CookieManager, CookiePolicy, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.jdk.CollectionConverters.*

class ToolHttp(base: String) extends AutoCloseable {
  private val cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL)
  private val client = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(20)).build()
  def request(path: String, body: Option[Json] = None, csrf: Boolean = true): ToolResponse = {
    val request = HttpRequest.newBuilder(URI.create(base.stripSuffix("/") + path)).timeout(Duration.ofSeconds(60)).header("Content-Type", "application/json")
    if (csrf) request.header("X-LogiCov-Request", "1")
    body.foreach(value => request.POST(HttpRequest.BodyPublishers.ofString(JsonFormatter.Compact(value))))
    val response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray())
    // Max-Age makes the JDK default to obsolete versioned cookies; send the browser-compatible form.
    cookies.getCookieStore.getCookies.asScala.foreach(_.setVersion(0))
    ToolResponse(response)
  }
  def data(path: String): Json = request(path).expect(200).json
  def login(email: String, password: String): Unit = {
    request("/api/auth/login", Some(Map("email" -> email, "password" -> password).json)).expect(200)
    ()
  }
  override def close(): Unit = client.close()
}
