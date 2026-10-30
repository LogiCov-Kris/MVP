package logicov.workspace

import fabric.rw.*

case class GenerationProgress(phase: GenerationPhase = GenerationPhase.Preparing, sections: List[String] = Nil, titles: List[String] = MemoSections.titles) derives RW
