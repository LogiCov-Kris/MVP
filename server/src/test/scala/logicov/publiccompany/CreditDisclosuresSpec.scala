package logicov.publiccompany

import logicov.workspace.{ProfileField, WorkspaceDocument}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CreditDisclosuresSpec extends AnyFlatSpec with Matchers {
  private val document = WorkspaceDocument("source", "sec-quarterly.htm", "text/html", "hash", 100, "2026-11-01")
  private val disclosure = """<p>Our Credit Agreement had a maturity date of September 30, 2030, with Example Bank, N.A., as administrative agent.
    On October 6, 2026, we obtained consent from the Lenders to an amendment to extend the maturity date of the Credit Facility by one year to September 30, 2031.
    The Credit Facility provides for aggregate commitments of $ 3.5 billion, with incremental capacity of $1.0 billion.
    There was approximately $ 3.1 billion available for borrowings.
    The Credit Agreement requires our compliance with an indebtedness to capitalization ratio, which is total debt divided by debt plus equity, not to exceed 65 %.</p>"""

  "Credit disclosures" should "retain the source and distinguish amended maturity, total commitments and capitalization" in {
    val candidates = CreditDisclosures.read(document, disclosure)
    candidates.size should be(1)
    val found = candidates.head
    found.documentId should be("source")
    val values = found.suggestions.map(s => s.field -> s.value).toMap
    values(ProfileField.Commitment) should be("3500")
    values(ProfileField.Availability) should be("3100")
    values(ProfileField.Maturity) should be("September 30, 2031")
    values(ProfileField.Covenant) should be("1")
    BigDecimal(values(ProfileField.Threshold)) should be(BigDecimal("0.65"))
    found.excerpt should include("65 %")
    values.contains(ProfileField.Concentration) should be(false)
  }

  it should "leave ambiguous commitments and unsupported leverage definitions unfilled" in {
    CreditDisclosures.read(document, disclosure + "Aggregate commitments of $1.4 billion.") should be(Nil)
    val unsupported = disclosure.replace("indebtedness to capitalization", "total liabilities to tangible net worth")
    CreditDisclosures.read(document, unsupported).head.suggestions.exists(_.field == ProfileField.Covenant) should be(false)
  }

  it should "handle filing whitespace and numeric currency entities" in {
    val encoded = disclosure.replace("$ ", "&#36;&#160;").replace("65 %", "65&#160;%")
    CreditDisclosures.read(document, encoded).head.suggestions.find(_.field == ProfileField.Commitment).map(_.value) should be(Some("3500"))
  }
}
