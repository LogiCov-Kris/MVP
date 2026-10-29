package logicov.workspace

import fabric.rw.*
import logicov.grid.GridCorrection

case class DealCorrectionRequest(revision: Long, correction: GridCorrection) derives RW
