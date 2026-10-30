package logicov.workspace

import fabric.rw.*

enum DealStage derives RW { case Review, Approved, Generating, Completed, Failed, Submitted }
