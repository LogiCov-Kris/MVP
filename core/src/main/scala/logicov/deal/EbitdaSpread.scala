package logicov.deal

import fabric.rw.*

/**
 * A LogiCov EBITDA spread: the `base` XBRL concept plus each of the `addBacks` concepts, for each fiscal year.
 */
case class EbitdaSpread(base: String, addBacks: List[String]) derives RW
