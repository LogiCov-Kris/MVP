package logicov.workspace

import fabric.rw.*

case class NarrativeRequest(revision: Long, section: String, text: String, reason: String) derives RW
