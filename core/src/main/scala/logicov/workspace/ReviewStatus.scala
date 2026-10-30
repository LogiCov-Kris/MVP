package logicov.workspace

import fabric.rw.*

enum ReviewStatus derives RW { case Pending, Clear, Stop }
