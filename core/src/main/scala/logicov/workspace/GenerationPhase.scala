package logicov.workspace

import fabric.rw.*

enum GenerationPhase derives RW { case Preparing, Rendering, Printing, Packaging }
