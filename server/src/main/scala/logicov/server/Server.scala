package logicov.server

import logicov.App
import logicov.server.service.SystemServices
import rapid.Task
import spice.http.server.CORSSupport
import spice.http.server.config.HttpServerListener
import spice.openapi.server.{OpenAPIHttpServer, Service}

object Server extends OpenAPIHttpServer with CORSSupport {
  override def title: String = "LogiCov API"
  override def version: String = logicov.BuildInfo.version
  override def description: Option[String] = Some("LogiCov backend")

  override def services: List[Service] = SystemServices.list

  override protected def initialize(): Task[Unit] = Task.defer {
    config.listeners @= List(HttpServerListener(host = App.config.host, port = Some(App.config.port)))
    super.initialize()
  }
}
