package logicov.model

import fabric.rw.*

case class PasswordResetConfirmation(email: String, token: String, password: String) derives RW
