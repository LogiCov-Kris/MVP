package logicov.server

import fabric.rw.*
import rapid.Task
import spice.http.HttpMethod
import spice.net.URLPath
import spice.openapi.server.{OpenAPIHttpServer, Service, ServiceCall}

trait ServiceSupport {
  protected def tag: String

  def list: List[Service]

  protected def service[Req, Res](path: String, summary: String, method: HttpMethod = HttpMethod.Post)
                                 (handler: Req => Task[Res])(using RW[Req], RW[Res]): Service = {
    val servicePath = path
    new Service {
      override def server: OpenAPIHttpServer = Server
      override val path: URLPath = URLPath.parse(servicePath)
      override val calls: List[ServiceCall] = List(
        serviceCall[Req, Res](
          method = method,
          summary = summary,
          description = summary,
          successDescription = "OK",
          tags = List(tag)
        ) { request =>
          handler(request.request).flatMap(request.response)
        }
      )
    }
  }
}
