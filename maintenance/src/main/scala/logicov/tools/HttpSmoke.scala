package logicov.tools

import fabric.io.JsonParser
import fabric.rw.*
import java.util.Base64

object HttpSmoke {
  def run(): Unit = {
    val client = new ToolHttp(sys.env.getOrElse("LOGICOV_TEST_URL", "http://127.0.0.1:8300"))
    try {
      client.request("/api/health").expect(200)
      client.request("/api/ready").expect(200)
      val page = client.request("/").expect(200).text
      require(page.contains("LogiCov") && page.contains("main"), "Frontend missing")
      client.request("/api/session").expect(401)
      client.request("/api/import", Some(Map.empty[String, String].json)).expect(401)
      val email = sys.env("LOGICOV_TEST_EMAIL")
      val credentials = Map("email" -> email, "password" -> sys.env("LOGICOV_TEST_PASSWORD"))
      client.request("/api/auth/login", Some(credentials.json), csrf = false).expect(403)
      client.request("/api/auth/login", Some(credentials.updated("password", "incorrect").json)).expect(401)
      val cookie = client.request("/api/auth/login", Some(credentials.json)).expect(200).header("Set-Cookie")
      require(cookie.contains("HttpOnly") && cookie.contains("SameSite=Strict"), "Session cookie security attributes missing")
      val session = client.request("/api/session").expect(200)
      require(session.header("Cache-Control") == "no-store" && session.json("email").asString == email.toLowerCase, "Session response mismatch")
      val encoded = Base64.getEncoder.encodeToString("Revenue,1200\nCash,50".getBytes("UTF-8"))
      val payload = JsonParser(s"""{"filename":"financials.csv","data":"$encoded","options":{"entity":"HTTP Example","unit":"Millions","columns":[{"column":2,"label":"FY2025","start":"2025-01-01","end":"2025-12-31"}]}}""")
      client.request("/api/import", Some(payload), csrf = false).expect(403)
      val imported = client.request("/api/import", Some(payload)).expect(200)
      require(imported.header("Cache-Control") == "no-store", "Import response must not be cached")
      val values = ToolFiles.array(imported.json("grid")("values"))
      require(values.size == 2 && ToolFiles.array(imported.json("issues")).isEmpty, "Unexpected import result")
      require(values.head("source")("location").asString == "record 1, column 2", "Missing citation")
      client.request("/api/import", Some(payload.asObj.value.updated("filename", "../financials.csv".json).json)).expect(400)
      client.request("/api/import", Some(payload.asObj.value.updated("data", "not-base64!".json).json)).expect(400)
      client.request("/api/auth/logout", Some(Map.empty[String, String].json), csrf = false).expect(403)
      client.request("/api/session").expect(200)
      client.request("/api/auth/logout", Some(Map.empty[String, String].json)).expect(200)
      client.request("/api/session").expect(401)
      client.request("/%2e%2e/build.sbt").expect(404)
      client.request("/api/unknown").expect(404)
      println("HTTP smoke passed: assets, readiness, authentication, session, CSRF, upload, validation, logout and path boundary")
    } finally client.close()
  }
}
