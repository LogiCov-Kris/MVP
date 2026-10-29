package logicov.analysis

import fabric.rw.*
import logicov.source.Sourced

case class Facility(kind: String, action: FacilityAction, maturity: Sourced[String], agent: Sourced[String]) derives RW
