package logicov.workspace

import fabric.rw.*

case class PolicySet(name: String, version: String, source: String, rules: List[PolicyRule]) derives RW {
  def validate(): Unit = {
    require(name.trim.nonEmpty && version.trim.nonEmpty && source.trim.nonEmpty, "Policy name, version and source are required")
    require(rules.nonEmpty && rules.size <= 100, "A policy must contain 1 to 100 rules")
    require(rules.map(_.id).distinct.size == rules.size, "Policy rule identifiers must be unique")
    require(rules.forall(r => r.id.nonEmpty && r.description.nonEmpty), "Every rule needs an identifier and description")
  }
}
