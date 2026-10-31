package logicov.workspace

import fabric.rw.*
import logicov.analysis.SampleDeals
import logicov.memo.{MemoBlock, MemoRenderer, MemoSection, NarrativeBasis, NarrativeGenerator, NarrativeText}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import rapid.Task

import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

class WorkspaceNarrativesSpec extends AnyFlatSpec with Matchers {
  private def deal: WorkspaceDeal = {
    val record = SampleDeals.kinderMorgan
    WorkspaceDeal("draft", record.borrower, DealStage.Approved, 1, record.grid, Some(record), Nil, Nil, Nil,
      approval = Some(ApprovalRecord("analyst@example.test", "2026-10-30", 1, "Reviewed")))
  }
  private def cached[A](run: Path => A): A = {
    val path = Files.createTempDirectory("narrative-spec-")
    try run(path) finally {
      val files = Files.walk(path)
      try files.iterator().asScala.toList.reverse.foreach(Files.delete) finally files.close()
    }
  }
  "Workspace drafting" should "require explicit provider and retention approvals for record mode" in {
    WorkspaceNarratives.configured(Map.empty) should be(NarrativeMode.Disabled)
    val enabled = Map("LOGICOV_NARRATIVES_ENABLED" -> "true", "LOGICOV_PROVIDER_APPROVED" -> "true")
    WorkspaceNarratives.configured(enabled) should be(NarrativeMode.PublicOnly)
    intercept[IllegalArgumentException](WorkspaceNarratives.configured(enabled - "LOGICOV_PROVIDER_APPROVED"))
    val full = enabled.updated("LOGICOV_RECORD_NARRATIVES_ENABLED", "true")
    intercept[IllegalArgumentException](WorkspaceNarratives.configured(full))
    intercept[IllegalArgumentException](WorkspaceNarratives.configured(full.updated("LOGICOV_RETENTION_APPROVED", "true") - "LOGICOV_NARRATIVES_ENABLED"))
    WorkspaceNarratives.configured(full.updated("LOGICOV_RETENTION_APPROVED", "true")) should be(NarrativeMode.ApprovedRecord)
  }
  it should "preserve financial block placement and every section evidence link after serialization" in {
    val generated = WorkspaceDraft.blockSections.filter(_._2 != "Recommendation").map { (key, _) =>
      key -> NarrativeText(s"Retained analysis for block $key", Nil, promptHash = "prompt", basis = NarrativeBasis.ApprovedRecord)
    }
    val saved = deal.copy(generatedNarratives = generated).json.as[WorkspaceDeal]
    val draft = WorkspaceDraft.prepare(saved)
    draft.template.sections(1).blocks should be(WorkspaceDraft.narrativeTemplate.sections(1).blocks)
    val review = draft.review
    generated.foreach { (key, _) =>
      val title = WorkspaceDraft.blockSections(key)
      review.sections.find(_.title == title).getOrElse(fail(s"Missing section $title")).html should include(s"#narrative-$key")
      review.appendix should include(s"id=\"evidence-narrative-$key\"")
    }
    val financial = draft.template.sections(1).blocks
    financial(9) shouldBe a[MemoBlock.Narrative]
    financial(10) should be(MemoBlock.Table("balance-sheet"))
    saved.narratives should be(empty)
  }
  it should "keep authored text independent of generated drafts and use the analyst recommendation" in {
    val original = deal.copy(narratives = Map("Executive Summary" -> "Analyst authored assessment"),
      generatedNarratives = Map("0.0" -> NarrativeText("Old generated assessment", Nil, promptHash = "old", basis = NarrativeBasis.ApprovedRecord)),
      recommendation = CreditRecommendation.Decline, recommendationBasis = "Repayment risk remains unresolved")
    val draft = WorkspaceDraft.prepare(original)
    draft.context.narratives("0.0").text should be("Analyst authored assessment")
    draft.context.narratives("0.0").promptHash should be("")
    draft.context.narratives("9.0").text should include(CreditRecommendation.Decline.label)
    draft.context.narratives("9.0").text should include(original.recommendationBasis)
    val edited = original.copy(narratives = original.narratives.updated("Recommendation", draft.texts("Recommendation") + "\n\nAuthored explanation"))
    val rebuilt = WorkspaceDraft.prepare(edited)
    rebuilt.texts("Recommendation").sliding("{{recommendation}}".length).count(_ == "{{recommendation}}") should be(1)
    rebuilt.context.narratives("9.0").text should include("Authored explanation")
  }
  it should "draft only missing blocks and retain reviewed drafts on submission without another provider request" in cached { cache =>
    val calls = new AtomicInteger()
    val original = deal.copy(narratives = Map("Executive Summary" -> "Authored summary", "Key Risk Factors and Mitigants" -> "Authored risks"))
    val generated = WorkspaceNarratives.approved(original, cache, complete = (_, _) => {
      calls.incrementAndGet()
      Task.pure("The analysis reflects the approved financial record.")
    }).sync()
    generated.size should be(12)
    generated.values.foreach { text =>
      text.promptHash.length should be(64)
      text.basis should be(NarrativeBasis.ApprovedRecord)
    }
    val saved = original.copy(generatedNarratives = generated, recommendation = CreditRecommendation.Conditional)
    WorkspaceNarratives.approved(saved, cache, complete = (_, _) => Task.error(new IllegalStateException("Must reuse reviewed prose"))).sync() should be(generated)
    calls.get() should be(12)
    intercept[IllegalArgumentException](WorkspaceNarratives.approved(original.copy(approval = None), cache))
  }
  it should "reject unsupported figures and empty provider responses before saving drafts" in cached { cache =>
    val original = deal.copy(narratives = WorkspaceDraft.sectionTitles.filterNot(_ == "Executive Summary").map(_ -> "Authored section").toMap)
    val calls = new AtomicInteger()
    val drafts = WorkspaceNarratives.approved(original, cache, complete = (_, _) => Task.pure {
      calls.incrementAndGet() match {
        case 1 => "Revenue is $987654321 million."
        case 2 => ""
        case _ => "The analysis reflects the approved financial record."
      }
    }).sync()
    calls.get() should be(3)
    drafts("0.0").unverified should be(Nil)
  }
  it should "reject a generated credit recommendation even when its figures match the record" in cached { cache =>
    val original = deal.copy(narratives = WorkspaceDraft.sectionTitles.filterNot(_ == "Executive Summary").map(_ -> "Authored section").toMap)
    val calls = new AtomicInteger()
    intercept[IllegalStateException] {
      WorkspaceNarratives.approved(original, cache, complete = (_, _) => {
        calls.incrementAndGet()
        Task.pure("The analyst recommends approval.")
      }).sync()
    }
    calls.get() should be(3)
  }

  it should "rewrite list formatting and excess length before accepting an executive summary" in cached { cache =>
    val original = deal.copy(narratives = WorkspaceDraft.sectionTitles.filterNot(_ == "Executive Summary").map(_ -> "Authored section").toMap)
    val calls = new AtomicInteger()
    val drafts = WorkspaceNarratives.approved(original, cache, complete = (_, _) => Task.pure {
      calls.incrementAndGet() match {
        case 1 => "- **The analysis reflects the record.**"
        case 2 => List.fill(300)("evidence").mkString(" ")
        case _ => "The analysis reflects the approved financial record."
      }
    }).sync()
    calls.get() should be(3)
    drafts("0.0").text should be("The analysis reflects the approved financial record.")
  }

  it should "preserve explicitly reviewed prose without imposing new drafting style limits" in {
    val template = WorkspaceDraft.narrativeTemplate.copy(sections = List(MemoSection("Reviewed prose",
      List(MemoBlock.Narrative("Analyst", "Write at most 3 words")))))
    val prose = "The analyst retained this reviewed assessment without changing its wording."
    NarrativeGenerator.reuse(template, WorkspaceDraft.context(deal), Map("0.0" -> prose))("0.0").text should be(prose)
  }

}
