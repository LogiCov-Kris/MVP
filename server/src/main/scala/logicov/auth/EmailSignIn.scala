package logicov.auth

import logicov.App
import logicov.db.DB

import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.{Base64, UUID}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration

object EmailSignIn {
  def enabled: Boolean = sys.env.get("LOGICOV_EMAIL_SIGNIN_ENABLED").contains("true")
  lazy val links: EmailLinks = new EmailLinks(deliver, sys.env.getOrElse("LOGICOV_PUBLIC_URL", ""))

  lazy val resetLinks: EmailLinks = new EmailLinks(deliver, sys.env.getOrElse("LOGICOV_PUBLIC_URL", ""), purpose = EmailPurpose.Reset)

  def initialize(): Unit = {
    if (!App.config.production) migrate()
    if (enabled) {
      require(sys.env.get("LOGICOV_EMAIL_API_KEY").exists(_.nonEmpty), "Email API key required")
      require(sys.env.get("LOGICOV_EMAIL_FROM").exists(_.nonEmpty), "Email sender required")
      links
      endpoint
      DB.connections.jdbc { connection =>
        val statement = connection.createStatement()
        try { statement.executeQuery("SELECT digest FROM email_signin_tokens LIMIT 0").close() }
        finally statement.close()
      }
    }
  }

  def migrate(): Unit = DB.connections.jdbc { connection =>
    val statement = connection.createStatement()
    try {
      statement.execute("SELECT pg_advisory_xact_lock(1845249092)")
      statement.execute("CREATE TABLE IF NOT EXISTS email_signin_tokens (digest TEXT PRIMARY KEY, user_id TEXT NOT NULL, expires_at BIGINT NOT NULL, identity_hash TEXT NOT NULL)")
      statement.execute("ALTER TABLE email_signin_tokens ADD COLUMN IF NOT EXISTS identity_hash TEXT NOT NULL DEFAULT ''")
      statement.execute("ALTER TABLE email_signin_tokens ADD COLUMN IF NOT EXISTS purpose TEXT NOT NULL DEFAULT 'SignIn'")
      statement.execute("CREATE TABLE IF NOT EXISTS email_signin_requests (email_hash TEXT NOT NULL, requested_at BIGINT NOT NULL)")
    } finally statement.close()
  }

  private def endpoint: URI = {
    val domain = sys.env.getOrElse("LOGICOV_EMAIL_DOMAIN", "")
    require(domain.matches("[A-Za-z0-9.-]+"), "Email domain required")
    val region = sys.env.getOrElse("LOGICOV_EMAIL_REGION", "US")
    require(Set("US", "EU").contains(region), "Email region must be US or EU")
    val host = if (region == "EU") "api.eu.mailgun.net" else "api.mailgun.net"
    val provider = s"https://$host/v3/$domain/messages"
    val value = sys.env.getOrElse("LOGICOV_EMAIL_API_URL", provider)
    require(value == provider || (!App.config.production && value.matches("http://(127\\.0\\.0\\.1|localhost):[0-9]+/emails")), "Email endpoint must be the provider or a local test server")
    URI.create(value)
  }

  private def deliver(address: String, link: String): Unit = {
    val reset = link.contains("#reset=")
    val subject = if (reset) "Reset your LogiCov password" else "Sign in to LogiCov"
    val instruction = if (reset) "reset your LogiCov password" else "sign in to LogiCov"
    deliverMessage(address, subject, s"Use this link to $instruction:\n\n$link\n\nThe link expires in 10 minutes and can be used once. Enter this email address on the page to continue. If you did not request it, you can ignore this email.")
  }

  def invite(address: String, tenant: String): Unit = {
    require(enabled, "Email invitations are not configured.")
    val origin = sys.env.getOrElse("LOGICOV_PUBLIC_URL", "")
    require(origin.matches("https://[^/#?]+") || (!App.config.production && origin.matches("http://(127\\.0\\.0\\.1|localhost):[0-9]+")), "Trusted application URL required")
    deliverMessage(address, "You're invited to LogiCov", s"You have been invited to the $tenant workspace in LogiCov.\n\nOpen $origin and enter $address to request a secure sign-in link. You can also use Forgot password to set a password.\n\nYour workspace administrator manages your access.")
  }

  private def deliverMessage(address: String, subject: String, text: String): Unit = {
    val boundary = "mail-" + UUID.randomUUID().toString
    val fields = List("from" -> sys.env("LOGICOV_EMAIL_FROM"), "to" -> address, "subject" -> subject,
      "text" -> text,
      "o:tracking" -> "no", "o:tracking-clicks" -> "no", "o:tracking-opens" -> "no")
    val payload = fields.map { case (name, value) =>
      s"--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n"
    }.mkString + s"--$boundary--\r\n"
    val authorization = Base64.getEncoder.encodeToString(("api:" + sys.env("LOGICOV_EMAIL_API_KEY")).getBytes(StandardCharsets.UTF_8))
    val request = HttpRequest.newBuilder(endpoint)
      .timeout(Duration.ofSeconds(15)).header("Authorization", s"Basic $authorization")
      .header("Content-Type", s"multipart/form-data; boundary=$boundary")
      .POST(HttpRequest.BodyPublishers.ofString(payload)).build()
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    try {
      val response = client.send(request, HttpResponse.BodyHandlers.discarding())
      require(response.statusCode() >= 200 && response.statusCode() < 300, s"Email provider returned status ${response.statusCode()}")
    } finally client.close()
  }
}
