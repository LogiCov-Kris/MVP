package logicov.workspace

import fabric.rw.*
import logicov.source.Source

case class ProfileSuggestion(field: ProfileField, value: String, explanation: String, sources: List[Source]) derives RW
