package logicov.memo

import fabric.io.JsonParser
import fabric.rw.*
import logicov.analysis.{CreditAnalysis, SampleDeals}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.time.LocalDate

class MemoRendererSpec extends AnyFlatSpec with Matchers {
  private val template = JsonParser(new String(
    getClass.getClassLoader.getResourceAsStream("templates/public-revolver.json").readAllBytes(), StandardCharsets.UTF_8)).as[MemoTemplate]

  "The internal outlook" should "follow the guidance rule for the three samples" in {
    List(SampleDeals.kinderMorgan -> "Stable", SampleDeals.westlake -> "Watchful", SampleDeals.occidental -> "Positive").foreach {
      case (record, outlook) => MemoContext.build(CreditAnalysis.run(record), LocalDate.of(2026, 10, 8), "POC").value("rating.outlook") should be(outlook)
    }
  }

  "The public-revolver template" should "render every sample deal with all bindings resolved" in {
    List(SampleDeals.kinderMorgan -> "4 (BBB / Baa2)", SampleDeals.westlake -> "4 (BBB / Baa2)", SampleDeals.occidental -> "4- (BBB- / Baa3)").foreach {
      case (record, grade) =>
        val context = MemoContext.build(CreditAnalysis.run(record), LocalDate.of(2026, 10, 8), s"${record.ticker}-POC")
        val html = MemoRenderer.render(template, context)
        html should include(grade)
        html should not include "{{"
    }
  }
  it should "escape analyst narrative markup without treating it as a template binding" in {
    val context = MemoContext.build(CreditAnalysis.run(SampleDeals.occidental), LocalDate.of(2026, 10, 26), "review")
    val key = template.sections.zipWithIndex.flatMap { (section, sectionIndex) =>
      section.blocks.zipWithIndex.collect { case (MemoBlock.Narrative(_, _), blockIndex) => MemoRenderer.narrativeKey(sectionIndex, blockIndex) }
    }.head
    val text = "<img src=\"http://127.0.0.1/private\"> {{not-a-binding}}"
    val html = MemoRenderer.render(template, context.copy(narratives = Map(key -> NarrativeText(text, Nil))))
    html should include("&lt;img")
    html should include("{{not-a-binding}}")
    html should not include "<img"
  }

}
