package logicov.analysis

case class StressRow(scenario: StressScenario,
                     ebitda: BigDecimal,
                     netDebt: BigDecimal,
                     leverage: Option[BigDecimal],
                     covenantHeadroom: Option[BigDecimal],
                     interestCoverage: Option[BigDecimal],
                     dividendCover: BigDecimal,
                     cashAfterCapexAndDividends: BigDecimal)
