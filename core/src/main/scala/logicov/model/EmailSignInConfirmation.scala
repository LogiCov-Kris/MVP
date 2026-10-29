package logicov.model

import fabric.rw.*

case class EmailSignInConfirmation(email: String, token: String) derives RW
