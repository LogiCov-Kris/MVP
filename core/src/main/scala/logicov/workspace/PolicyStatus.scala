package logicov.workspace

import fabric.rw.*

enum PolicyStatus derives RW {
  case Pass, Breach, Unavailable, NotConfigured, NotApplicable

  def label: String = this match {
    case NotConfigured => "Not configured"
    case NotApplicable => "Not applicable"
    case _ => toString
  }
}
