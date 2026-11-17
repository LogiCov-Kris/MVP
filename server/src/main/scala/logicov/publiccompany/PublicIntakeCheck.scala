package logicov.publiccompany

import fabric.io.JsonFormatter
import fabric.rw.*
import logicov.edgar.EdgarClient
import logicov.workspace.CreditRequirements

import java.nio.file.{Files, Path}

object PublicIntakeCheck {
  def main(args: Array[String]): Unit = {
    require(args.length >= 2, "Usage: PublicIntakeCheck output-directory ticker...")
    val output = Files.createDirectories(Path.of(args.head))
    val source = new PublicCompanySource(new EdgarClient(sys.env.getOrElse("EDGAR_USER_AGENT",
      throw new IllegalArgumentException("Configure the SEC contact"))))
    val results = args.drop(1).toList.map { ticker =>
      val issuer = source.lookup(ticker)
      val snapshot = source.fetch(issuer.cik)
      val folder = Files.createDirectories(output.resolve(issuer.cik.toString))
      snapshot.sources.foreach((name, _, bytes) => Files.write(folder.resolve(name), bytes))
      Files.writeString(folder.resolve("grid.json"), JsonFormatter.Default(snapshot.grid.json))
      val years = snapshot.grid.values.filter(_.period.label.startsWith("FY")).map(_.period.end.getYear).distinct.sorted
      val missing = years.lastOption.toList.flatMap(CreditRequirements.required).filter((item, period) => snapshot.grid.get(item, period).isEmpty)
      val result = Map("issuer" -> issuer.json, "citedValues" -> snapshot.grid.values.size.json, "annualYears" -> years.json,
        "retainedSources" -> snapshot.sources.size.json, "missingCreditValues" -> missing.map((item, period) => s"$item (${period.label})").json,
        "methodologyReview" -> issuer.methodologyIssue.json, "facilityInputs" -> "Analyst confirmation required; no terms invented".json).json
      println(JsonFormatter.Compact(result))
      result
    }
    Files.writeString(output.resolve("intake-report.json"), JsonFormatter.Default(results.json))
  }
}
