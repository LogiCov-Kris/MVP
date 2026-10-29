package logicov.model

import fabric.rw.*

enum UserRole derives RW {
  case Analyst, Admin
}
