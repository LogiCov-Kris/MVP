package logicov.grid

import fabric.rw.*

case class GridRevision(original: GridValue, correction: GridCorrection) derives RW
