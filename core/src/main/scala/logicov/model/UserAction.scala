package logicov.model

import fabric.rw.*

enum UserAction derives RW {
  case Create, Update, RevokeSessions, Invite, InvitationSent, InvitationFailed
}
