package logicov.deal

import fabric.rw.*

/**
 * Points at a figure in the discovery call: the clause of `field` that contains `marker`, described to readers as `label`.
 */
case class CallReference(field: String, marker: String, label: String) derives RW
