package logicov.deal

import logicov.source.Source
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class OfficerExtractorSpec extends AnyFlatSpec with Matchers {
  private val section = "Information about our Executive Officers James Y. Chao (age 78) . Mr. Chao has been our Senior Chairman " +
    "of the Board of Directors since July 2024 and became a director in June 2003. From July 2004 to July 2024, Mr. Chao served as Chairman."
  private val source = Source.Exhibit("10-K", "0001262823-26-000016", "executive officers", "as written")

  "An extracted officer" should "be accepted only when every field is verbatim in the filing" in {
    val chao = ExecutiveOfficer("James Y. Chao", "Senior Chairman of the Board of Directors", Some(78),
      "Mr. Chao has been our Senior Chairman of the Board of Directors since July 2024 and became a director in June 2003.", source)
    OfficerExtractor.verified(section, chao) should be(true)
    OfficerExtractor.verified(section, chao.copy(background = "Mr. Chao is a seasoned chemicals executive.")) should be(false)
    OfficerExtractor.verified(section, chao.copy(age = Some(68))) should be(false)
  }
}
