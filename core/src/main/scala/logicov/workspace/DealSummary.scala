package logicov.workspace

import fabric.rw.*

case class DealSummary(id: String, title: String, stage: DealStage, revision: Long, values: Int, issues: Int, updatedAt: String) derives RW
