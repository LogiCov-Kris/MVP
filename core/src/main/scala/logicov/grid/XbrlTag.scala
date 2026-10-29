package logicov.grid

/** An XBRL concept, optionally net of the `less` concepts and plus the `plus` concepts; every named concept must be present. */
case class XbrlTag(name: String, negate: Boolean = false, less: List[String] = Nil, plus: List[String] = Nil)
