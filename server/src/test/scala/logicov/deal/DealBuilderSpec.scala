package logicov.deal

import logicov.analysis.{FacilityAction, SampleDeals}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DealBuilderSpec extends AnyFlatSpec with Matchers {
  private val kmi = SampleDeals.kinderMorgan.profile
  private val wlk = SampleDeals.westlake.profile
  private val oxy = SampleDeals.occidental.profile

  "The deal builder" should "read segment revenue and the Company's segment earnings measure from the 10-K" in {
    val gas = kmi.segments.find(_.name == "Natural Gas Pipelines").get
    gas.sales(2025) should be(BigDecimal(11009))
    gas.earnings(2025) should be(BigDecimal(6080))
    kmi.segments.map(_.name) should contain("CO2")
    wlk.segments.find(_.name == "Performance and Essential Materials").get.earnings(2025) should be(BigDecimal(-2100))
  }

  it should "take EBITDA from the basis the analyst names" in {
    kmi.ltmEbitda.get.value should be(BigDecimal(9000))
    wlk.annualEbitda.map(_.value) should be(List(BigDecimal(2587), BigDecimal(2286), BigDecimal(1144)))
    oxy.annualEbitda.map(_.value) should be(List(BigDecimal(12718), BigDecimal(12775), BigDecimal(12049)))
  }

  it should "read the facility, availability and covenant" in {
    kmi.facility.action should be(FacilityAction.Hold)
    kmi.facility.agent.value should be("Barclays Bank PLC")
    kmi.facility.maturity.value should be("May 21, 2031")
    oxy.facility.action should be(FacilityAction.AmendAndExtend)
    oxy.facility.maturity.value should be("target 2031 (per management; current maturity June 30, 2028)")
    wlk.facility.agent.value should be("JPMorgan Chase Bank, N.A.")
    kmi.availability.value should be(BigDecimal(3200))
    oxy.availability.value should be(BigDecimal(4150))
    wlk.covenant.indicative should be(true)
  }

  it should "parse every agency rating with its outlook" in {
    wlk.agencyRatings.map(a => (a.house, a.rating, a.outlook)) should be(List(
      ("S&P", "BBB", "Negative"), ("Moody's", "Baa2", "Stable"), ("Fitch", "BBB", "Negative")
    ))
    oxy.agencyRatings.map(_.rating) should be(List("BB+", "Baa3", "BBB"))
  }

  it should "build the note schedule from the filing whose instruments reconcile to its total" in {
    val kmiNotes = kmi.fundedDebt.find(_.instrument == "Senior Notes").get
    kmiNotes.status should include("Dec 31, 2025")
    wlk.fundedDebt.find(_.instrument == "Senior Notes").get.status should startWith("5,160 outstanding at Jun 30, 2026")
    wlk.fundedDebt.find(_.instrument == "Senior Notes").get.terms should include("maturities 2027 to 2061")
    kmi.fundedDebt.find(_.instrument == "Commercial paper").get.status should be("338 at Jun 30, 2026")
  }

  it should "read contract life from the 10-K and concentration from the call when the analyst points there" in {
    kmi.contractLifeYears.map(_.value) should be(Some(BigDecimal(7)))
    wlk.contractLifeYears should be(None)
    oxy.largestShare.value should be(BigDecimal("0.55"))
    oxy.priceLinkedShare.value should be(BigDecimal(1))
  }
}
