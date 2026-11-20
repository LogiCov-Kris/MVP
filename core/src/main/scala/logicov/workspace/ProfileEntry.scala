package logicov.workspace

import fabric.rw.*

case class ProfileEntry(field: ProfileField, value: String) derives RW
