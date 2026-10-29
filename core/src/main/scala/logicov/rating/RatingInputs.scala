package logicov.rating

case class RatingInputs(ladder: Ladder,
                        ebitda: BigDecimal,
                        scaleEbitda: BigDecimal,
                        netLeverage: BigDecimal,
                        fcfToDebt: BigDecimal,
                        interestCoverage: BigDecimal,
                        liquidityCover: BigDecimal,
                        netCash: Boolean,
                        largestShare: BigDecimal,
                        concentrationBasis: String,
                        commodityModifier: Option[String],
                        priceLinkedShare: BigDecimal,
                        severeTrough: Boolean,
                        support: Support)
