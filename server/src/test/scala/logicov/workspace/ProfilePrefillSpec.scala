package logicov.workspace

import logicov.grid.{GoldenGrid, GridValue, LineItem, Period}
import logicov.source.Source
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ProfilePrefillSpec extends AnyFlatSpec with Matchers {
  "Profile prefilling" should "derive unadjusted earnings from complete annual components with provenance" in {
    val values = (2023 to 2025).toList.flatMap { year =>
      List(LineItem.PretaxIncome -> BigDecimal(-10), LineItem.InterestExpense -> BigDecimal(5), LineItem.Depreciation -> BigDecimal(20))
        .map((item, value) => GridValue(item, Period.fiscalYear(year), value, Source.Xbrl(item.toString, "10-K", year.toString)))
    }
    val grid = GoldenGrid("Example", values)
    val suggestion = ProfilePrefill.annualEbitda(grid, 2025).getOrElse(fail("Expected cited earnings"))
    suggestion.value should be("15, 15, 15")
    suggestion.sources.size should be(9)
    suggestion.explanation should include("unadjusted")
    ProfilePrefill.annualEbitda(grid.copy(values = values.tail), 2025) should be(None)
  }
}
