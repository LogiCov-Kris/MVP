package logicov.tools

import com.sun.net.httpserver.HttpServer
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.net.InetSocketAddress

class ToolHttpSpec extends AnyFlatSpec with Matchers {
  "HTTP verification client" should "retain Max-Age session cookies in browser-compatible form" in {
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/login", exchange => {
      exchange.getResponseHeaders.set("Set-Cookie", "logicov=test-session; Path=/; HttpOnly; SameSite=Strict; Max-Age=3600")
      exchange.sendResponseHeaders(200, -1)
      exchange.close()
    })
    server.createContext("/session", exchange => {
      val cookie = Option(exchange.getRequestHeaders.getFirst("Cookie")).getOrElse("")
      exchange.sendResponseHeaders(if (cookie == "logicov=test-session") 200 else 401, -1)
      exchange.close()
    })
    server.start()
    val client = new ToolHttp(s"http://127.0.0.1:${server.getAddress.getPort}")
    try {
      client.request("/session").status should be(401)
      client.request("/login").status should be(200)
      client.request("/session").status should be(200)
    } finally {
      client.close()
      server.stop(0)
    }
  }
}
