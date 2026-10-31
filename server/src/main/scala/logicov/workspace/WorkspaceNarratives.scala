package logicov.workspace

import logicov.ai.LogiCovSigil
import logicov.memo.{MemoBlock, MemoRenderer, NarrativeBasis, NarrativeGenerator, NarrativeText, PublicNarratives}
import rapid.Task

import java.nio.file.Path

object WorkspaceNarratives {
  def configured(env: Map[String, String]): NarrativeMode = {
    val enabled = env.get("LOGICOV_NARRATIVES_ENABLED").contains("true")
    val records = env.get("LOGICOV_RECORD_NARRATIVES_ENABLED").contains("true")
    require(!records || enabled, "Full-record drafting also requires narrative generation to be enabled")
    require(!enabled || env.get("LOGICOV_PROVIDER_APPROVED").contains("true"), "Approve the client-owned provider for this environment")
    require(!records || env.get("LOGICOV_RETENTION_APPROVED").contains("true"), "Confirmed provider retention approval is required for full-record drafting")
    if (!enabled) NarrativeMode.Disabled else if (records) NarrativeMode.ApprovedRecord else NarrativeMode.PublicOnly
  }

  def generate(deal: WorkspaceDeal, cache: Path, progress: GenerationProgress => Unit): Map[String, NarrativeText] = {
    configured(sys.env) match {
      case NarrativeMode.Disabled => Map.empty
      case NarrativeMode.ApprovedRecord => approved(deal, cache, progress).sync()
      case NarrativeMode.PublicOnly =>
        val retained = deal.generatedNarratives.filter((_, n) => n.basis == NarrativeBasis.PublicEvidence)
        val available = retained.keySet.flatMap(key => WorkspaceDraft.blockSections.get(key))
        val record = deal.record.getOrElse(throw new IllegalArgumentException("Credit profile required"))
        val fresh = PublicNarratives.generate(record.cik, PublicNarratives.Sections -- deal.narratives.keySet -- available).sync()
        retained ++ fresh.flatMap { (title, text) => WorkspaceDraft.blockSections.toList.sortBy(_._1).find(_._2 == title).map((key, _) => key -> text) }
    }
  }

  private[workspace] def approved(deal: WorkspaceDeal, cache: Path, progress: GenerationProgress => Unit = _ => (),
                                  complete: (String, String) => Task[String] = LogiCovSigil.complete): Task[Map[String, NarrativeText]] = {
    val record = deal.record.getOrElse(throw new IllegalArgumentException("Credit profile required"))
    require(deal.approval.nonEmpty, "Approved financial inputs are required before drafting")
    val retained = deal.generatedNarratives.filter { (key, text) =>
      text.basis == NarrativeBasis.ApprovedRecord && WorkspaceDraft.blockSections.get(key).exists(title => !deal.narratives.contains(title) && title != "Recommendation")
    }
    val template = WorkspaceDraft.narrativeTemplate.copy(sections = WorkspaceDraft.narrativeTemplate.sections.zipWithIndex.map { (section, i) =>
      section.copy(blocks = section.blocks.zipWithIndex.map {
        case (MemoBlock.Narrative(_, guidance), j) if !deal.narratives.contains(section.title) && section.title != "Recommendation" && !retained.contains(MemoRenderer.narrativeKey(i, j)) =>
          MemoBlock.Narrative("Approved record", guidance.replace("Lead with the recommendation and the proposed facility action", "Lead with the proposed facility action") + " Do not use recommendation language. Do not choose or imply a credit recommendation; that decision belongs to the analyst. Explain only the supplied evidence, without filling gaps with assumptions.")
        case (_: MemoBlock.Narrative, _) => MemoBlock.Paragraph("")
        case (block, _) => block
      })
    })
    val pending = WorkspaceDraft.blockSections.filter((key, title) => title != "Recommendation" && !deal.narratives.contains(title) && !retained.contains(key))
    var finished = Set.empty[String]
    def update(key: String): Unit = synchronized {
      finished += key
      val titles = WorkspaceDraft.sectionTitles.filter(title => pending.filter(_._2 == title).keys.forall(finished.contains))
      progress(GenerationProgress(GenerationPhase.Drafting, titles, WorkspaceDraft.sectionTitles))
    }
    progress(GenerationProgress(GenerationPhase.Drafting, Nil, WorkspaceDraft.sectionTitles))
    val context = WorkspaceDraft.context(deal)
    NarrativeGenerator.generate(template, context, record.inputs.narrativeNotes, cache, complete, update).map(retained ++ _)
  }
}
