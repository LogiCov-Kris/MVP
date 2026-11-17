package logicov.publiccompany

import fabric.io.JsonParser
import logicov.analysis.SampleDeals
import logicov.grid.LineItem
import logicov.source.Source
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files
import java.time.LocalDate

class PublicFinancialsSpec extends AnyFlatSpec with Matchers {
  "Public financial intake" should "retain reported amounts and never substitute a quarterly period for an annual period" in {
    val raw = JsonParser("""{"cik":123,"entityName":"Example","facts":{"us-gaap":{"Revenues":{"units":{"USD":[
      {"start":"2025-01-01","end":"2025-12-31","val":120000000,"form":"10-K","accn":"annual","filed":"2026-02-01"},
      {"start":"2025-10-01","end":"2025-12-31","val":30000000,"form":"10-K","accn":"annual","filed":"2026-02-01"},
      {"start":"2026-01-01","end":"2026-06-30","val":65000000,"form":"10-Q","accn":"interim","filed":"2026-08-01"},
      {"start":"2026-04-01","end":"2026-06-30","val":32000000,"form":"10-Q","accn":"interim","filed":"2026-08-01"}
    ]}}}}}""")
    val grid = PublicFinancials.grid(raw, LocalDate.of(2025, 12, 31))
    grid.values.map(_.value) should be(List(BigDecimal(120), BigDecimal(65)))
    grid.values.map(_.period.label) should be(List("FY2025", "YTD 2026-06-30"))
    grid.values.forall(_.source.isInstanceOf[Source.Xbrl]) should be(true)
    grid.values.exists(_.item == LineItem.Cash) should be(false)
  }

  it should "extract all reference annual periods without requiring analyst facility inputs" in {
    List("kmi", "wlk", "oxy").foreach { ticker =>
      val path = SampleDeals.pack(ticker).sec.resolve("companyfacts.json")
      val grid = PublicFinancials.grid(JsonParser(Files.readString(path)), LocalDate.of(2025, 12, 31))
      grid.values.filter(_.period.label.startsWith("FY")).map(_.period.label).distinct should be(List("FY2023", "FY2024", "FY2025"))
      grid.values.size should be > 50
    }
  }

  "Issuer methodology checks" should "block unknown, financial and noncalendar classifications without guessing a rating" in {
    PublicIssuer(1, "EX", "Example", "1311", "1231").methodologyIssue should be(None)
    PublicIssuer(1, "EX", "Example", "", "1231").methodologyIssue should not be empty
    PublicIssuer(1, "EX", "Example", "6799", "1231").methodologyIssue should not be empty
    PublicIssuer(1, "EX", "Example", "1311", "0630").methodologyIssue should not be empty
  }
}
