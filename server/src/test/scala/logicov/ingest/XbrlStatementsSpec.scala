package logicov.ingest

import logicov.grid.LineItem
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.LocalDate

class XbrlStatementsSpec extends AnyFlatSpec with Matchers {
  private val options = ImportOptions("Borrower", AmountUnit.Thousands, List(ImportColumn(2, "FY2025", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31))))
  private def instance(facts: String): Array[Byte] = s"""<xbrl xmlns="http://www.xbrl.org/2003/instance" xmlns:us-gaap="http://fasb.org/us-gaap/2025" xmlns:custom="https://example.test/custom" xmlns:iso4217="http://www.xbrl.org/2003/iso4217"><context id="year"><entity><identifier scheme="https://example.test">Borrower</identifier></entity><period><startDate>2025-01-01</startDate><endDate>2025-12-31</endDate></period></context><unit id="usd"><measure>iso4217:USD</measure></unit>$facts</xbrl>""".getBytes("UTF-8")
  "Uploaded XBRL" should "use instance USD amounts and cite matched contexts without guessing custom tags" in {
    val result = StatementImporter.run("statement.xbrl", instance("<us-gaap:Revenues contextRef=\"year\" unitRef=\"usd\">120000000</us-gaap:Revenues><custom:NetIncomeLoss contextRef=\"year\" unitRef=\"usd\">999</custom:NetIncomeLoss>"), options)
    result.grid.values.find(_.item == LineItem.Revenue).map(_.value) should be(Some(BigDecimal(120)))
    result.grid.values.exists(_.item == LineItem.NetIncomeAttributable) should be(false)
    result.grid.values.head.source.describe should include("Revenues")
  }
  it should "flag conflicting facts and reject document entities" in {
    val result = StatementImporter.run("statement.xml", instance("<us-gaap:Revenues contextRef=\"year\" unitRef=\"usd\">1</us-gaap:Revenues><us-gaap:Revenues contextRef=\"year\" unitRef=\"usd\">2</us-gaap:Revenues>"), options)
    result.grid.values should be(empty)
    result.issues.exists(_.kind == ImportIssueKind.Duplicate) should be(true)
    intercept[Exception](StatementImporter.run("statement.xbrl", "<!DOCTYPE xbrl [<!ENTITY test SYSTEM 'file:///etc/passwd'>]><xbrl>&test;</xbrl>".getBytes("UTF-8"), options))
  }
}
