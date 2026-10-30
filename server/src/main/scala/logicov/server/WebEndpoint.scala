package logicov.server

import fabric.io.{JsonFormatter, JsonParser}
import fabric.rw.*
import logicov.App
import logicov.auth.{AuthService, LoginLimiter, EmailSignIn}
import logicov.db.DB
import logicov.ingest.{ImportRequest, StatementImporter}
import logicov.model.{ApiError, EmptyRequest, LoginRequest, AuthOptions, EmailSignInRequest, EmailSignInConfirmation, PasswordResetConfirmation}
import rapid.Task
import spice.http.{HttpExchange, HttpMethod, HttpStatus}
import spice.http.content.Content
import spice.http.server.dsl.{ConnectionFilter, FilterResponse}
import spice.http.server.handler.SenderHandler
import spice.net.ContentType

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.Base64
import java.util.concurrent.Semaphore
import scala.util.Try

object WebEndpoint extends ConnectionFilter {
  private val importing = new Semaphore(1)
  private val hashing = new Semaphore(2)
  private def cookieName: String = if (App.config.production) "__Host-logicov" else "logicov"
  private[server] def token(exchange: HttpExchange): String = exchange.request.cookies.find(_.name == cookieName).fold("")(_.value)
  private def header(exchange: HttpExchange, name: String): Option[String] =
    exchange.request.headers.map.get(name).flatMap(_.headOption)

  private def cookie(value: String, seconds: Long): String =
    s"$cookieName=$value; Path=/; HttpOnly; SameSite=Strict; Max-Age=$seconds${if (App.config.production) "; Secure" else ""}"

  override def apply(exchange: HttpExchange)(using scribe.mdc.MDC): Task[FilterResponse] = {
    val path = exchange.request.url.path.decoded
    val method = exchange.request.method
    val action = (method, path) match {
      case (_, p) if p == "/api/deals" || p.startsWith("/api/deals/") => WorkspaceEndpoint(exchange)
      case (HttpMethod.Get, "/api/ready") => Task(DB.connections.healthy()).flatMap { healthy =>
        json(exchange, if (healthy) 200 else 503, EmptyRequest())
      }
      case (HttpMethod.Get, "/api/auth/options") => json(exchange, 200, AuthOptions(EmailSignIn.enabled))
      case (HttpMethod.Post, "/api/auth/email/request") if mutationAllowed(exchange) => emailRequest(exchange)
      case (HttpMethod.Post, "/api/auth/email/confirm") if mutationAllowed(exchange) => emailConfirm(exchange)
      case (HttpMethod.Post, "/api/auth/email/request" | "/api/auth/email/confirm") => error(exchange, 403, "Request not permitted.")
      case (HttpMethod.Post, "/api/auth/password/request") if mutationAllowed(exchange) => emailRequest(exchange, reset = true)
      case (HttpMethod.Post, "/api/auth/password/reset") if mutationAllowed(exchange) => passwordReset(exchange)
      case (HttpMethod.Post, "/api/auth/password/request" | "/api/auth/password/reset") => error(exchange, 403, "Request not permitted.")
      case (_, p) if p.startsWith("/api/admin/") => AdminEndpoint(exchange)
      case (HttpMethod.Get, "/api/session") => AuthService.current(token(exchange)).flatMap {
        case Some(session) => json(exchange, 200, session)
        case None => error(exchange, 401, "Please sign in.")
      }
      case (HttpMethod.Post, "/api/import") if mutationAllowed(exchange) => upload(exchange)
      case (HttpMethod.Post, "/api/import") => error(exchange, 403, "Request not permitted.")
      case (HttpMethod.Post, "/api/auth/login") if mutationAllowed(exchange) => login(exchange)
      case (HttpMethod.Post, "/api/auth/logout") if mutationAllowed(exchange) =>
        AuthService.signOut(token(exchange)).flatMap(_ => json(exchange, 200, EmptyRequest(), Some(cookie("", 0))))
      case (HttpMethod.Post, "/api/auth/login" | "/api/auth/logout") => error(exchange, 403, "Request not permitted.")
      case (_, p) if p.startsWith("/api/") && p != "/api/health" => error(exchange, 404, "Not found.")
      case (HttpMethod.Get, p) if !p.startsWith("/api/") => asset(exchange, p)
      case _ => Task.pure(continue(exchange))
    }
    action.handleError { cause =>
      scribe.error("Request failed", cause)
      error(exchange, if (path == "/api/ready") 503 else 500, "The service could not complete this request.")
    }
  }

  private[server] def mutationAllowed(exchange: HttpExchange): Boolean =
    header(exchange, "X-LogiCov-Request").contains("1") &&
      header(exchange, "Content-Type").exists(_.takeWhile(_ != ';').trim.equalsIgnoreCase("application/json"))

  private def login(exchange: HttpExchange): Task[FilterResponse] = exchange.request.content match {
    case None => error(exchange, 400, "Email and password required.")
    case Some(body) => body.asStream.take(8193).toList.flatMap { bytes =>
      if (bytes.size > 8192) error(exchange, 413, "Request too large.")
      else Try(JsonParser(new String(bytes.toArray, StandardCharsets.UTF_8)).as[LoginRequest]).toOption match {
        case None => error(exchange, 400, "Email and password required.")
        case Some(request) =>
          val key = AuthService.email(request.email).take(254)
          if (!LoginLimiter.allow(key) || !hashing.tryAcquire()) error(exchange, 429, "Too many sign-in attempts. Try again later.")
          else AuthService.signIn(request.email, request.password).flatMap {
            case Some(value) => json(exchange, 200, EmptyRequest(), Some(cookie(value, AuthService.SessionSeconds)))
            case None => error(exchange, 401, "Email or password is incorrect.")
          }.guarantee(Task(hashing.release()).unit)
      }
    }
  }

  private def emailBody[A: RW](exchange: HttpExchange)(run: A => Task[FilterResponse]): Task[FilterResponse] = {
    if (!EmailSignIn.enabled) error(exchange, 503, "Email sign-in is not available yet. Please use your password.")
    else exchange.request.content match {
      case None => error(exchange, 400, "Sign-in details required.")
      case Some(body) => body.asStream.take(8193).toList.flatMap { bytes =>
        if (bytes.size > 8192) error(exchange, 413, "Request too large.")
        else Try(JsonParser(new String(bytes.toArray, StandardCharsets.UTF_8)).as[A]).toOption match {
          case Some(value) => run(value)
          case None => error(exchange, 400, "Sign-in details required.")
        }
      }
    }
  }

  private def emailRequest(exchange: HttpExchange, reset: Boolean = false): Task[FilterResponse] = emailBody[EmailSignInRequest](exchange) { request =>
    val normalized = AuthService.email(request.email)
    if (normalized.length > 254 || !normalized.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) error(exchange, 400, "Enter a valid email address.")
    else (if (reset) EmailSignIn.resetLinks else EmailSignIn.links).request(normalized).handleError { cause =>
      scribe.error("Sign-in email request failed", cause)
      Task.unit
    }.flatMap(_ => json(exchange, 200, EmptyRequest()))
  }

  private def emailConfirm(exchange: HttpExchange): Task[FilterResponse] = emailBody[EmailSignInConfirmation](exchange) { request =>
    if (!LoginLimiter.allow("email:" + AuthService.email(request.email).take(254))) error(exchange, 429, "Too many sign-in attempts. Try again later.")
    else EmailSignIn.links.confirm(request.email, request.token).flatMap {
      case Some(value) => json(exchange, 200, EmptyRequest(), Some(cookie(value, AuthService.SessionSeconds)))
      case None => error(exchange, 401, "This sign-in link is invalid or expired. Request a new link.")
    }
  }

  private def passwordReset(exchange: HttpExchange): Task[FilterResponse] = emailBody[PasswordResetConfirmation](exchange) { request =>
    if (request.password.length < 14 || request.password.length > 256) error(exchange, 400, "Passwords must contain 14 to 256 characters.")
    else if (!LoginLimiter.allow("reset:" + AuthService.email(request.email).take(254)) || !hashing.tryAcquire()) {
      error(exchange, 429, "Too many attempts. Try again later.")
    } else EmailSignIn.resetLinks.reset(request.email, request.token, request.password).flatMap {
      case true => json(exchange, 200, EmptyRequest(), Some(cookie("", 0)))
      case false => error(exchange, 401, "This reset link is invalid or expired. Request a new link.")
    }.guarantee(Task(hashing.release()).unit)
  }

  private def upload(exchange: HttpExchange): Task[FilterResponse] = AuthService.current(token(exchange)).flatMap {
    case None => error(exchange, 401, "Please sign in.")
    case Some(_) if !importing.tryAcquire() => error(exchange, 429, "Another import is running. Please try again shortly.")
    case Some(_) => (exchange.request.content match {
      case None => error(exchange, 400, "Choose a financial statement.")
      case Some(body) => body.asStream.take(7 * 1024 * 1024 + 1).toList.flatMap { bytes =>
        if (bytes.size > 7 * 1024 * 1024) error(exchange, 413, "Upload exceeds 5 MB.")
        else Task {
          Try {
            val request = JsonParser(new String(bytes.toArray, StandardCharsets.UTF_8)).as[ImportRequest]
            StatementImporter.run(request.filename, Base64.getDecoder.decode(request.data), request.options)
          }.toEither
        }.flatMap {
          case Right(result) => json(exchange, 200, result)
          case Left(cause: IllegalArgumentException) => error(exchange, 400, cause.getMessage.take(500))
          case Left(_) => error(exchange, 400, "The statement could not be read. Check its format, encoding and password protection.")
        }
      }
    }).guarantee(Task(importing.release()).unit)
  }

  private[server] def error(exchange: HttpExchange, status: Int, message: String): Task[FilterResponse] =
    json(exchange, status, ApiError(message))

  private[server] def json[A: RW](exchange: HttpExchange, status: Int, value: A, sessionCookie: Option[String] = None): Task[FilterResponse] =
    exchange.withContent(Content.string(JsonFormatter.Compact(value.json), ContentType.`application/json`))
      .flatMap(_.modify(r => Task.pure {
        val response = r.withStatus(HttpStatus.byCode(status)).withHeader("Cache-Control", "no-store")
          .withHeader("X-Content-Type-Options", "nosniff")
        sessionCookie.fold(response)(c => response.withHeader("Set-Cookie", c))
      })).map(stop)

  private def asset(exchange: HttpExchange, requested: String): Task[FilterResponse] = Task.defer {
    val root = Path.of(App.config.webDirectory).toAbsolutePath.normalize()
    val relative = if (requested == "/" || requested == "/admin") "index.html" else requested.stripPrefix("/")
    val file = root.resolve(relative).normalize()
    if (!file.startsWith(root) || !Files.isRegularFile(file) || !file.toRealPath().startsWith(root.toRealPath())) {
      error(exchange, 404, "Not found.")
    } else SenderHandler(Content.file(file.toFile), replace = true).handle(exchange)
      .flatMap(_.modify(r => Task.pure(r.withHeader("Cache-Control", if (relative == "index.html" || relative == "main.js") "no-cache" else "public, max-age=3600")
        .withHeader("X-Content-Type-Options", "nosniff").withHeader("X-Frame-Options", "DENY")
        .withHeader("Referrer-Policy", "same-origin")
        .withHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"))))
      .map(stop)
  }
}
