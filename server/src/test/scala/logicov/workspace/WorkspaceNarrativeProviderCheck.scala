package logicov.workspace

import fabric.io.JsonFormatter
import fabric.rw.*
import logicov.ai.LogiCovSigil
import logicov.analysis.{CovenantKind, FacilityAction}
import logicov.config.LogiCovConfig
import logicov.deal.ContextDocument
import logicov.ingest.{AmountUnit, ImportColumn, ImportOptions, StatementImporter}
import logicov.memo.{MemoRenderer, PdfPrinter}
import logicov.rating.Support
import spice.http.client.HttpClient

import java.nio.file.Files
import java.time.LocalDate
import java.util.Base64

object WorkspaceNarrativeProviderCheck {
  def main(args: Array[String]): Unit = {
    require(args.isEmpty, "This check accepts only its built-in synthetic fixture")
    require(sys.env.get("LOGICOV_PROVIDER_APPROVED").contains("true"), "Provider approval is required for synthetic testing")
    val statement = "Revenue,200,180,160\nOperatingCashFlow,22,20,18\nCashCapex,5,4,3\nInterestExpense,3,3,3\nDebtPrincipal,40,35,30\nCurrentDebt,5,5,5\nLongTermDebtCarrying,35,30,25\nCash,10,8,6\nTotalEquity,60,55,50\nDividendsPaid,2,2,2\n".getBytes("UTF-8")
    val options = ImportOptions("Synthetic Manufacturing", AmountUnit.Millions, List(2025, 2024, 2023).zipWithIndex.map { (year, index) =>
      ImportColumn(index + 2, s"FY$year", LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31))
    })
    val imported = StatementImporter.run("synthetic.csv", statement, options)
    val document = WorkspaceDocument("source", "synthetic.csv", "text/csv", "a" * 64, statement.length, "2026-10-30T00:00:00Z")
    val original = WorkspaceDeal("synthetic", "Synthetic Manufacturing", DealStage.Approved, 1, imported.grid, None, imported.issues, List(document), Nil,
      approval = Some(ApprovalRecord("synthetic@example.test", "2026-10-30", 1, "Synthetic verification only")))
    val request = CreditSetupRequest(1, "SYNTHETIC", 2025, "Senior revolver", FacilityAction.New, "October 26, 2029", "Example lender",
      20, 10, CovenantKind.MaxNetLeverage, 4, List(20, 25, 30), BigDecimal("0.4"), 0, "Largest customer revenue share", Support.Neutral,
      "Independent ownership", false, "source", "Synthetic financial statements and facility schedule", "Synthetic verification only")
    val record = CreditSetup.build(original, request)
    val description = "This is a fictional manufacturing borrower used only for software verification. Revenue growth reflects additional orders from existing customers. The company produces industrial components under renewable supply agreements. Customer concentration is a risk; its largest customer has a long operating relationship. Materials costs can fluctuate; contracts permit periodic price resets. Working capital is seasonal; the proposed revolver supports inventory needs. The founder oversees operations with a long-serving finance director. No officer biographies or external ratings are supplied. There are no acquisitions or disposals in this synthetic scenario. The company competes on delivery reliability; no quantified peer data or forecast is supplied."
    val deal = original.copy(record = Some(record.copy(contextDocuments = List(ContextDocument("Synthetic business context", description)))))
    val directory = Files.createTempDirectory("synthetic-drafting-")
    LogiCovConfig.load()
    LogiCovSigil.instance.sync()
    try {
      val drafts = WorkspaceNarratives.approved(deal, directory.resolve("cache")).sync()
      require(drafts.size == 14 && drafts.values.forall(_.unverified.isEmpty), "Every analytical block must pass numeric checks")
      val draft = WorkspaceDraft.prepare(deal, drafts)
      val html = MemoRenderer.render(draft.template, draft.context)
      val input = Files.writeString(directory.resolve("memo.html"), html)
      val pdf = new PdfPrinter("/usr/local/bin/chromium").print(input, directory.resolve("memo.pdf"))
      drafts.toList.sortBy(_._1).foreach { (key, text) => println(s"SYNTHETIC_NARRATIVE $key ${JsonFormatter.Compact(text.json)}") }
      List("html" -> html.getBytes("UTF-8"), "pdf" -> Files.readAllBytes(pdf)).foreach { (kind, bytes) =>
        Base64.getEncoder.encodeToString(bytes).grouped(24000).zipWithIndex.foreach { (chunk, index) => println(s"SYNTHETIC_ARTIFACT $kind $index $chunk") }
      }
      println("SYNTHETIC_CHECK_PASSED: 14 analytical blocks and PDF; no client data supplied")
    } finally {
      try LogiCovSigil.shutdown.sync() finally HttpClient.dispose().sync()
    }
  }
}
