package logicov.ui

import fabric.io.{JsonFormatter, JsonParser}
import fabric.rw.*
import logicov.ingest.{ImportRequest, ImportResult}
import logicov.workspace.*
import logicov.model.{ApiError, EmptyRequest, LoginRequest, SessionView, WorkspaceUser, UserRequest, ManagedWorkspace, WorkspaceRequest, AuthOptions, EmailSignInRequest, EmailSignInConfirmation, PasswordResetConfirmation}
import org.scalajs.dom.XMLHttpRequest

import scala.util.Try

object ApiClient {
  def users(done: Either[String, List[WorkspaceUser]] => Unit): Unit = get("/api/admin/users")(done)
  def workspaceUsers(id: String)(done: Either[String, List[WorkspaceUser]] => Unit): Unit = get(s"/api/admin/workspaces/$id/users")(done)
  def manageUser(value: UserRequest, workspaceId: String = "")(done: Either[String, List[WorkspaceUser]] => Unit): Unit =
    post(if (workspaceId.isEmpty) "/api/admin/users" else s"/api/admin/workspaces/$workspaceId/users", value)(done)
  def workspaces(done: Either[String, List[ManagedWorkspace]] => Unit): Unit = get("/api/admin/workspaces")(done)
  def manageWorkspace(value: WorkspaceRequest)(done: Either[String, List[ManagedWorkspace]] => Unit): Unit = post("/api/admin/workspaces", value)(done)

  def deals(done: Either[String, List[DealSummary]] => Unit): Unit = get("/api/deals")(done)
  def deal(id: String)(done: Either[String, WorkspaceDeal] => Unit): Unit = get(s"/api/deals/$id")(done)
  def create(value: CreateDealRequest)(done: Either[String, WorkspaceDeal] => Unit): Unit = post("/api/deals", value)(done)
  def action[A: RW](id: String, action: String, value: A)(done: Either[String, WorkspaceDeal] => Unit): Unit =
    post(s"/api/deals/$id/$action", value)(done)

  private def get[A: RW](path: String)(done: Either[String, A] => Unit): Unit =
    request("GET", path, "") { (status, body) => decode(status, body)(done) }

  private def post[A: RW, B: RW](path: String, value: A)(done: Either[String, B] => Unit): Unit =
    request("POST", path, JsonFormatter.Compact(value.json)) { (status, body) => decode(status, body)(done) }

  private def decode[A: RW](status: Int, body: String)(done: Either[String, A] => Unit): Unit =
    if (status >= 200 && status < 300) done(Try(JsonParser(body).as[A]).toEither.left.map(_ => "Invalid workspace response."))
    else done(Left(message(body)))

  def upload(value: ImportRequest)(done: Either[String, ImportResult] => Unit): Unit =
    request("POST", "/api/import", JsonFormatter.Compact(value.json)) { (status, body) =>
      if (status == 200) done(Try(JsonParser(body).as[ImportResult]).toEither.left.map(_ => "Invalid import response."))
      else done(Left(message(body)))
    }

  def session(done: Either[String, Option[SessionView]] => Unit): Unit =
    request("GET", "/api/session", "") { (status, body) =>
      if (status == 401) done(Right(None))
      else if (status == 200) done(Try(JsonParser(body).as[SessionView]).toEither.left.map(_ => "Invalid server response.").map(Some(_)))
      else done(Left(message(body)))
    }

  def login(email: String, password: String)(done: Option[String] => Unit): Unit =
    request("POST", "/api/auth/login", JsonFormatter.Compact(LoginRequest(email, password).json)) { (status, body) =>
      done(if (status == 200) None else Some(message(body)))
    }

  def authOptions(done: Either[String, AuthOptions] => Unit): Unit = get("/api/auth/options")(done)

  def requestEmail(email: String)(done: Option[String] => Unit): Unit =
    post[EmailSignInRequest, EmptyRequest]("/api/auth/email/request", EmailSignInRequest(email))(r => done(r.left.toOption))

  def confirmEmail(email: String, token: String)(done: Option[String] => Unit): Unit =
    post[EmailSignInConfirmation, EmptyRequest]("/api/auth/email/confirm", EmailSignInConfirmation(email, token))(r => done(r.left.toOption))

  def requestPasswordReset(email: String)(done: Option[String] => Unit): Unit =
    post[EmailSignInRequest, EmptyRequest]("/api/auth/password/request", EmailSignInRequest(email))(r => done(r.left.toOption))

  def resetPassword(email: String, token: String, password: String)(done: Option[String] => Unit): Unit =
    post[PasswordResetConfirmation, EmptyRequest]("/api/auth/password/reset", PasswordResetConfirmation(email, token, password))(r => done(r.left.toOption))

  def logout(done: Option[String] => Unit): Unit =
    request("POST", "/api/auth/logout", JsonFormatter.Compact(EmptyRequest().json)) { (status, body) =>
      done(if (status == 200) None else Some(message(body)))
    }

  private def message(body: String): String =
    Try(JsonParser(body).as[ApiError].error).getOrElse("Unable to reach the service. Please try again.")

  private def request(method: String, path: String, body: String)(done: (Int, String) => Unit): Unit = {
    val xhr = new XMLHttpRequest()
    xhr.open(method, path)
    xhr.timeout = 120000
    if (method == "POST") {
      xhr.setRequestHeader("Content-Type", "application/json")
      xhr.setRequestHeader("X-LogiCov-Request", "1")
    }
    xhr.onload = _ => done(xhr.status, xhr.responseText)
    xhr.onerror = _ => done(0, "")
    xhr.ontimeout = _ => done(0, "")
    xhr.send(body)
  }
}
