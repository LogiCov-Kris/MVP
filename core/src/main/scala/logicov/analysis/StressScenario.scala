package logicov.analysis

import fabric.rw.*

case class StressScenario(name: String,
                          ebitdaChange: BigDecimal = 0,
                          ebitdaDelta: BigDecimal = 0,
                          addedDebt: BigDecimal = 0,
                          reverseToCovenant: Boolean = false,
                          revenueChange: Option[BigDecimal] = None,
                          costFlex: Option[BigDecimal] = None,
                          equityChange: BigDecimal = 0) derives RW {
  require(ebitdaChange >= -1 && ebitdaChange <= 0, "EBITDA reduction must be between 0% and 100%")
  require(revenueChange.forall(v => v >= -1 && v <= 0), "Revenue reduction must be between 0% and 100%")
  require(revenueChange.isDefined == costFlex.isDefined, "A revenue shock requires an explicit cost response")
  require(costFlex.forall(v => v >= 0 && v <= 1), "Cost response must be between 0 and 1")
  require(revenueChange.isEmpty || (ebitdaChange == 0 && ebitdaDelta == 0 && !reverseToCovenant),
    "Do not combine revenue and EBITDA shocks")
}
