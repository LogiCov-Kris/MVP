package logicov.deal

import logicov.analysis.CreditAnalysis
import logicov.grid.{GridCorrection, GridRevision, Period}
import logicov.source.Source

import java.time.Instant

object DealCorrections {
  def apply(record: DealRecord, corrections: List[GridCorrection]): DealRecord = {
    require(corrections.nonEmpty, "At least one correction is required")
    val revised = corrections.foldLeft(record) { (current, correction) =>
      require(correction.reason.trim.nonEmpty && correction.analyst.trim.nonEmpty, "Correction reason and analyst are required")
      Instant.parse(correction.recordedAt)
      correction.source match {
        case Source.Derived(_) => throw new IllegalArgumentException("A correction requires a source citation, not just a calculation")
        case Source.Filing(document) => require(document.trim.nonEmpty, "Source document is required")
        case Source.CallScript(field) => require(field.trim.nonEmpty, "Call field is required")
        case Source.Xbrl(tag, form, accession) => require(List(tag, form, accession).forall(_.trim.nonEmpty), "Complete filing citation required")
        case Source.Exhibit(form, accession, document, item) => require(List(form, accession, document, item).forall(_.trim.nonEmpty), "Complete exhibit citation required")
        case Source.Upload(document, sha, location, excerpt) =>
          require(List(document, location, excerpt).forall(_.trim.nonEmpty) && sha.matches("[a-f0-9]{64}"), "Complete upload citation required")
      }
      val matches = current.grid.values.filter(v => v.item == correction.item && v.period == correction.period)
      require(matches.size == 1, "Correction must identify exactly one existing grid value")
      val original = matches.head
      require(original.value == correction.expected, "The value changed since this correction was prepared")
      val delta = correction.value - original.value
      val initial = current.revisions.find(r => r.original.item == original.item && r.original.period == original.period)
        .fold(original)(_.original)
      val annual = current.profile.annualEbitda.map { figure =>
        val spread = current.inputs.ebitda.spread
        val tags = spread.toList.flatMap(s => s.base :: s.addBacks)
        val possible = original.item.tags.exists(t => tags.contains(t.name) || t.less.exists(tags.contains) || t.plus.exists(tags.contains))
        if (delta != 0 && possible && original.period == Period.fiscalYear(figure.year)) {
          val tag = initial.source match {
            case Source.Xbrl(name, _, _) => name
            case _ => throw new IllegalArgumentException("Rebuild the source pack to correct an EBITDA component without its original concept")
          }
          require(tags.contains(tag) && !tag.contains(" "), "Rebuild the source pack to correct a composite EBITDA component")
          val updated = figure.value + delta
          require(updated > 0, "The corrected base EBITDA must be positive; review the spread basis")
          figure.copy(value = updated, source = Source.Derived(s"${figure.source.describe}; ${correction.item} correction $delta million, ${correction.recordedAt}; see revision history"))
        } else figure
      }
      val updated = original.copy(value = correction.value, source = correction.source)
      current.copy(grid = current.grid.copy(values = current.grid.values.map(v => if (v == original) updated else v)),
        profile = current.profile.copy(annualEbitda = annual), revisions = current.revisions :+ GridRevision(original, correction))
    }
    val before = CreditAnalysis.run(record)
    val after = CreditAnalysis.run(revised)
    if (before.rating.machineGrade != after.rating.machineGrade && revised.profile.ratingOverride.nonEmpty) {
      revised.copy(inputs = revised.inputs.copy(ratingReview = Some("Financial correction changed the calculated risk rating; review the existing analyst override before approval.")))
    } else revised
  }
}
