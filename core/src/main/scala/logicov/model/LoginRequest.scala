package logicov.model

import fabric.rw.*

case class LoginRequest(email: String, password: String) derives RW
