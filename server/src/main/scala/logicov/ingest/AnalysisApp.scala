package logicov.ingest

import fabric.obj
import fabric.dsl.*
import fabric.io.{JsonFormatter, JsonParser}
import fabric.rw.*
import logicov.analysis.CreditAnalysis
import logicov.deal.DealRecord
import logicov.grid.GridValidation

import java.nio.file.{Files, Path, StandardOpenOption}

object AnalysisApp {
  def main(args: Array[String]): Unit = {
    require(args.length == 2, "Usage: AnalysisApp <deal.json> <new-analysis.json>")
    val record = JsonParser(Files.readString(Path.of(args(0)))).as[DealRecord]
    val result = CreditAnalysis.run(record)
    val report = obj("borrower" -> record.borrower, "ratios" -> obj(
      "ebitda" -> result.ratios.ebitda, "grossDebt" -> result.ratios.grossDebt,
      "netDebt" -> result.ratios.netDebt, "netLeverage" -> result.ratios.netLeverage,
      "interestCoverage" -> result.ratios.interestCoverage, "fcfToDebt" -> result.ratios.fcfToDebt,
      "liquidityCover" -> result.ratios.liquidityCover),
      "rating" -> obj("composite" -> result.rating.composite,
        "calculated" -> result.rating.machineGrade.mapping, "final" -> result.rating.grade.mapping,
        "expectedLoss" -> result.rating.expectedLoss),
      "covenant" -> obj("definition" -> result.covenant.covenant.json, "actual" -> result.covenant.actual,
        "headroom" -> result.covenant.headroom, "debtCapacity" -> result.covenant.debtCapacity),
      "gridIssues" -> GridValidation.check(record.grid).json,
      "corrections" -> record.revisions.json)
    Files.writeString(Path.of(args(1)), JsonFormatter.Default(report), StandardOpenOption.CREATE_NEW)
  }
}
