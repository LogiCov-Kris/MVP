package logicov.memo

import fabric.io.JsonParser
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import rapid.Task

class PublicNarrativesSpec extends AnyFlatSpec with Matchers {
  "Hosted public narratives" should "use only independently retrieved public evidence and supported sections" in {
    var prompts = List.empty[String]
    val company = PublicCompany("Public Example", "Manufacturing", Some("Jane Public is chief executive."), "https://www.sec.gov/example")
    val result = PublicNarratives.generate(123L, PublicNarratives.Sections ++ Set("Recommendation", "Financial Analysis"),
      _ => company, (system, prompt) => {
        prompts = prompts :+ prompt
        Task.pure("Public Example operates in its disclosed industry.")
      }).sync()
    result.keySet should be(PublicNarratives.Sections)
    prompts.size should be(2)
    prompts.forall(p => !p.contains("recommendation") && !p.contains("financial results:")) should be(true)
    prompts.exists(_.contains("Jane Public")) should be(true)
  }

  it should "make no external calls for private companies or manually supplied sections" in {
    val fetch: Long => PublicCompany = _ => fail("Unexpected public fetch")
    PublicNarratives.generate(0L, PublicNarratives.Sections, fetch).sync() should be(Map.empty)
    PublicNarratives.generate(123L, Set.empty, fetch).sync() should be(Map.empty)
  }

  it should "propagate provider failures and reject unsupported figures" in {
    val company = PublicCompany("Public Example", "Manufacturing", None, "https://www.sec.gov/example")
    intercept[IllegalStateException] {
      PublicNarratives.generate(123L, Set(PublicNarratives.Industry), _ => company,
        (_, _) => Task.error(new IllegalStateException("Provider unavailable"))).sync()
    }
    intercept[IllegalArgumentException] {
      PublicNarratives.generate(123L, Set(PublicNarratives.Industry), _ => company,
        (_, _) => Task.pure("Revenue was $987654 million.")).sync()
    }
  }

  it should "restrict filing retrieval to SEC locations and validate issuer identity" in {
    val data = JsonParser("""{"cik":"123","name":"Public Example","sicDescription":"Manufacturing","filings":{"recent":{"form":["10-K"],"accessionNumber":["0000000123-26-000001"],"primaryDocument":["annual.htm"]}}}""")
    val company = PublicCompany.fromSubmissions(data, 123L, url => {
      url should be("https://www.sec.gov/Archives/edgar/data/123/000000012326000001/annual.htm")
      "No officer listing."
    })
    company.officers should be(None)
    intercept[IllegalArgumentException](PublicCompany.fromSubmissions(data, 456L, _ => fail("Unexpected fetch")))
  }
}
