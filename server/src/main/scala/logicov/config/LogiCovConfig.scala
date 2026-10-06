package logicov.config

import profig.Profig

object LogiCovConfig {
  def load(): Unit = Profig.loadDefaults()
}
