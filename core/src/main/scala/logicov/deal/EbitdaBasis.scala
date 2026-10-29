package logicov.deal

import fabric.rw.*

/**
 * Where EBITDA comes from: a Company LTM print in the call, a row of the Company's earnings releases,
 * or a LogiCov spread from XBRL line items when the Company does not print EBITDA.
 */
case class EbitdaBasis(ltmField: Option[String], releaseLabel: Option[String], spread: Option[EbitdaSpread]) derives RW
