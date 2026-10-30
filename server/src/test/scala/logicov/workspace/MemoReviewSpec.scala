package logicov.workspace

import logicov.analysis.{CreditAnalysis, SampleDeals}
import logicov.memo.MemoRenderer
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MemoReviewSpec extends AnyFlatSpec with Matchers {
  private def deal: WorkspaceDeal = {
    val record = SampleDeals.kinderMorgan
    WorkspaceDeal("review", record.borrower, DealStage.Review, 1, record.grid, Some(record), Nil, Nil, Nil)
  }
  "Memo review" should "render shared record values and shared prose consistently without interpreting markup" in {
    val original = deal.copy(narratives = Map("Executive Summary" -> "Assessment for {{borrower}}: <script>alert(1)</script>",
      "Financial Analysis" -> "{{narrative.Executive Summary}}\n\nFinal rating: {{rating.mapping}}"))
    val draft = WorkspaceDraft.prepare(original)
    val review = draft.review
    review.sections.size should be(WorkspaceDraft.sectionTitles.size)
    review.sections.zipWithIndex.foreach { (section, index) => section.html should include(s"#narrative-$index.0") }
    review.sections.find(_.title == "Financial Analysis").map(_.html).getOrElse("") should include("Assessment for Kinder Morgan")
    val html = MemoRenderer.render(draft.template, draft.context)
    html should not include "<script>"
    html should include("&lt;script&gt;")
    val changed = WorkspaceDraft.prepare(original.copy(narratives = original.narratives.updated("Executive Summary", "Revised {{borrower}} assessment")))
    changed.review.sections.find(_.title == "Financial Analysis").map(_.html).getOrElse("") should include("Revised Kinder Morgan")
    changed.review.appendix should include("id=\"evidence-")
  }
  it should "reject unknown fields and cyclic shared text" in {
    intercept[IllegalArgumentException](WorkspaceDraft.validateText(deal, "Executive Summary", "{{missing.field}}"))
    val circular = deal.copy(narratives = Map("Executive Summary" -> "{{narrative.Financial Analysis}}", "Financial Analysis" -> "{{narrative.Executive Summary}}"))
    intercept[IllegalArgumentException](WorkspaceDraft.prepare(circular))
  }
}
