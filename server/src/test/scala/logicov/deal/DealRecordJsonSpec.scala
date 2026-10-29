package logicov.deal

import fabric.io.{JsonFormatter, JsonParser}
import fabric.rw.*
import logicov.analysis.{CreditAnalysis, SampleDeals}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DealRecordJsonSpec extends AnyFlatSpec with Matchers {
  "A DealRecord" should "round-trip through JSON and produce the same analysis" in {
    List(SampleDeals.kinderMorgan, SampleDeals.westlake, SampleDeals.occidental).foreach { record =>
      val json = JsonFormatter.Default(record.json)
      val restored = JsonParser(json).as[DealRecord]
      restored should be(record)
      CreditAnalysis.run(restored).rating.composite should be(CreditAnalysis.run(record).rating.composite)
    }
  }
}
