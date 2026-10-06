package logicov.server.service

import logicov.model.{EmptyRequest, HealthResponse}
import logicov.server.{Server, ServiceSupport}
import rapid.Task
import spice.http.HttpMethod
import spice.openapi.server.Service

object SystemServices extends ServiceSupport {
  override protected def tag: String = "system"

  val health: Service = service[EmptyRequest, HealthResponse]("/api/health", "Health check", HttpMethod.Get) { _ =>
    Task.pure(HealthResponse(ok = true, version = Server.version))
  }

  override def list: List[Service] = List(health)
}
