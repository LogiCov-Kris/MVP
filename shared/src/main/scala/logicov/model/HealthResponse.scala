package logicov.model

import fabric.rw.*

case class HealthResponse(ok: Boolean, version: String) derives RW
