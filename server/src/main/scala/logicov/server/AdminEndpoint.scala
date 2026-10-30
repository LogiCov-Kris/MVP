package logicov.server

import fabric.io.JsonParser
import fabric.rw.*
import logicov.auth.{AuthService, UserAdministration, WorkspaceAdministration}
import logicov.model.{UserRequest, UserRole, WorkspaceRequest}
import rapid.Task
import spice.http.{HttpExchange, HttpMethod}
import spice.http.server.dsl.{ConnectionFilter, FilterResponse}

import java.nio.charset.StandardCharsets
import java.util.concurrent.Semaphore
import scala.util.Try

object AdminEndpoint extends ConnectionFilter {
  private val writes = new Semaphore(2)

  override def apply(exchange: HttpExchange)(using scribe.mdc.MDC): Task[FilterResponse] = {
    val parts = exchange.request.url.path.decoded.stripPrefix("/api/admin/").split('/').filter(_.nonEmpty).toList
    val platform = parts.headOption.contains("workspaces")
    val workspaceId = parts match {
      case List("workspaces", id, "users") => id
      case _ => ""
    }
    val workspaceList = parts == List("workspaces")
    val valid = parts == List("users") || workspaceList || workspaceId.nonEmpty
    AuthService.current(WebEndpoint.token(exchange)).flatMap {
      case None => WebEndpoint.error(exchange, 401, "Please sign in.")
      case Some(actor) if actor.role != UserRole.Admin => WebEndpoint.error(exchange, 403, "Administrator access required.")
      case Some(actor) if platform && !actor.platformAdmin => WebEndpoint.error(exchange, 403, "Platform administrator access required.")
      case Some(_) if !valid => WebEndpoint.error(exchange, 404, "Not found.")
      case Some(actor) => exchange.request.method match {
        case HttpMethod.Get if workspaceList => WorkspaceAdministration.list(actor).flatMap(WebEndpoint.json(exchange, 200, _))
        case HttpMethod.Get => UserAdministration.list(actor, workspaceId).flatMap(WebEndpoint.json(exchange, 200, _))
        case HttpMethod.Post if !WebEndpoint.mutationAllowed(exchange) => WebEndpoint.error(exchange, 403, "Request not permitted.")
        case HttpMethod.Post if !writes.tryAcquire() => WebEndpoint.error(exchange, 429, "User administration is busy. Try again shortly.")
        case HttpMethod.Post => (exchange.request.content match {
          case None => WebEndpoint.error(exchange, 400, "Request body required.")
          case Some(body) => body.asStream.take(8193).toList.flatMap { bytes =>
            if (bytes.size > 8192) WebEndpoint.error(exchange, 413, "Request too large.")
            else if (workspaceList) Try(JsonParser(new String(bytes.toArray, StandardCharsets.UTF_8)).as[WorkspaceRequest]).toOption match {
              case None => WebEndpoint.error(exchange, 400, "Invalid workspace request.")
              case Some(request) => WorkspaceAdministration.change(actor, request).flatMap(_ => WorkspaceAdministration.list(actor))
                .flatMap(WebEndpoint.json(exchange, 200, _))
            } else Try(JsonParser(new String(bytes.toArray, StandardCharsets.UTF_8)).as[UserRequest]).toOption match {
              case None => WebEndpoint.error(exchange, 400, "Invalid account request.")
              case Some(request) => UserAdministration.change(actor, request, workspaceId = workspaceId).flatMap(_ => UserAdministration.list(actor, workspaceId))
                .flatMap(WebEndpoint.json(exchange, 200, _))
            }
          }
        }).guarantee(Task(writes.release()).unit)
        case _ => WebEndpoint.error(exchange, 404, "Not found.")
      }
    }.handleError {
      case _: NoSuchElementException => WebEndpoint.error(exchange, 404, "User not found.")
      case e: IllegalArgumentException => WebEndpoint.error(exchange, 400, e.getMessage.stripPrefix("requirement failed: ").take(500))
      case e: IllegalStateException =>
        scribe.error("User administration failed", e)
        WebEndpoint.error(exchange, 503, "Invitation could not be sent. Refresh the user list to see the saved account and retry later.")
      case e =>
        scribe.error("User administration failed", e)
        WebEndpoint.error(exchange, 500, "Unable to update users. Refresh and try again.")
    }
  }
}
