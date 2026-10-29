package logicov.memo

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class NarrativeCheckSpec extends AnyFlatSpec with Matchers {
  private val facts = "Net debt | 32,055\nLTM EBITDA: 9,000\nnet leverage: 3.56x\nPD: 0.14%"

  "NarrativeCheck" should "accept figures restated in billions or with units" in {
    NarrativeCheck.unverified("Net debt of $32.055 billion on EBITDA of $9.000 billion is 3.56x, PD 0.14%.", facts) should be(Nil)
  }

  it should "flag figures the model computed itself" in {
    NarrativeCheck.unverified("The notes total $1.850 billion and leverage would be 4.10x.", facts) should be(List("$1.850 billion", "4.10x"))
  }
}
