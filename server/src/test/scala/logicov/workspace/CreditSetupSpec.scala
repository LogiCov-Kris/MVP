package logicov.workspace

import logicov.analysis.{CovenantKind, CreditAnalysis, FacilityAction}
import logicov.grid.LineItem
import logicov.ingest.{AmountUnit, ImportColumn, ImportOptions, StatementImporter}
import logicov.memo.FinancialTables
import logicov.rating.Support
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.LocalDate

class CreditSetupSpec extends AnyFlatSpec with Matchers {
  private val statement = """Revenue,200,180,160
    |OperatingCashFlow,22,20,18
    |CashCapex,5,4,3
    |InterestExpense,3,3,3
    |DebtPrincipal,40,35,30
    |CurrentDebt,5,5,5
    |LongTermDebtCarrying,35,30,25
    |Cash,10,8,6
    |TotalEquity,60,55,50
    |DividendsPaid,2,2,2
    |""".stripMargin.getBytes("UTF-8")
  private val options = ImportOptions("Private borrower", AmountUnit.Millions, List(2025, 2024, 2023).zipWithIndex.map { (year, index) =>
    ImportColumn(index + 2, s"FY$year", LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31))
  })
  private val imported = StatementImporter.run("financials.csv", statement, options)
  private val document = WorkspaceDocument("source", "financials.csv", "text/csv", "a" * 64, statement.length, "2026-10-26T00:00:00Z")
  private val deal = WorkspaceDeal("deal", "Private borrower", DealStage.Review, 1, imported.grid, None, imported.issues, List(document), Nil)
  private val request = CreditSetupRequest(1, "PRIVATE", 2025, "Senior revolver", FacilityAction.New, "October 26, 2029", "Example lender",
    20, 10, CovenantKind.MaxNetLeverage, 4, List(20, 25, 30), BigDecimal("0.4"), 0, "Largest customer revenue share", Support.Neutral,
    "Independent ownership", false, "source", "Financial statements and facility schedule", "Analyst confirmed the profile")

  "A sourced credit profile" should "turn a three-year private-company grid into deterministic analysis without duplicate annual columns" in {
    val record = CreditSetup.build(deal, request)
    val analysis = CreditAnalysis.run(record)
    analysis.ratios.netLeverage should be(BigDecimal(1))
    analysis.ratios.interestCoverage should be(BigDecimal(10))
    analysis.ratios.fcfToDebt should be(BigDecimal("0.4"))
    analysis.ratios.scaleEbitda should be(BigDecimal(25))
    analysis.covenant.actual should be(BigDecimal(1))
    val table = FinancialTables.build(analysis)("financials")
    table.headers should be(List("", "FY2023", "FY2024", "FY2025"))
    record.profile.covenant.threshold.source.describe should include(document.sha256)
  }

  it should "refuse missing grid facts, unsourced profiles and unsupported zero denominators" in {
    intercept[IllegalArgumentException](CreditSetup.build(deal.copy(grid = deal.grid.copy(values = deal.grid.values.filterNot(_.item == LineItem.CashCapex))), request))
    intercept[IllegalArgumentException](CreditSetup.build(deal, request.copy(documentId = "unknown")))
    intercept[IllegalArgumentException](CreditSetup.build(deal, request.copy(annualEbitda = List(20, 25, 0))))
    intercept[IllegalArgumentException](CreditSetup.build(deal, request.copy(covenantKind = CovenantKind.MaxDebtToCapitalization, covenantThreshold = 60)))
  }
}
