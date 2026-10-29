package logicov.ingest

import fabric.io.{JsonFormatter, JsonParser}
import fabric.rw.*
import logicov.analysis.CreditAnalysis
import logicov.deal.{DealCorrections, DealRecord}
import logicov.grid.{GridCorrection, GridValidation}

import java.nio.file.{Files, Path, StandardOpenOption}

object CorrectionApp {
  def main(args: Array[String]): Unit = {
    require(args.length == 3, "Usage: CorrectionApp <deal.json> <corrections.json> <new-output-directory>")
    val record = JsonParser(Files.readString(Path.of(args(0)))).as[DealRecord]
    val corrections = JsonParser(Files.readString(Path.of(args(1)))).as[List[GridCorrection]]
    val updated = DealCorrections(record, corrections)
    val out = Path.of(args(2))
    Files.createDirectory(out)
    Files.writeString(out.resolve("original.json"), JsonFormatter.Default(record.json), StandardOpenOption.CREATE_NEW)
    Files.writeString(out.resolve("deal.json"), JsonFormatter.Default(updated.json), StandardOpenOption.CREATE_NEW)
    Files.writeString(out.resolve("corrections.json"), JsonFormatter.Default(corrections.json), StandardOpenOption.CREATE_NEW)
    Files.writeString(out.resolve("grid-issues.json"), JsonFormatter.Default(GridValidation.check(updated.grid).json), StandardOpenOption.CREATE_NEW)
    val before = CreditAnalysis.run(record)
    val after = CreditAnalysis.run(updated)
    val report = s"""Correction review for ${record.borrower}
      |All amounts are USD millions. Original values, sources, reasons and analyst are retained in deal.json revisions.
      |EBITDA: ${before.ratios.ebitda} -> ${after.ratios.ebitda}
      |Net debt: ${before.ratios.netDebt} -> ${after.ratios.netDebt}
      |Net leverage: ${before.ratios.netLeverage} -> ${after.ratios.netLeverage}
      |Risk rating: ${before.rating.grade} -> ${after.rating.grade}
      |Review: ${updated.inputs.ratingReview.getOrElse("Review the correction and regenerated analysis before approval.")}
      |Separately sourced call inputs, segment data, debt schedules and narratives retain their own evidence.
      |Regenerate the memo from deal.json after review; existing memo files have not been changed.
      |""".stripMargin
    Files.writeString(out.resolve("review.txt"), report, StandardOpenOption.CREATE_NEW)
    println(report)
  }
}
