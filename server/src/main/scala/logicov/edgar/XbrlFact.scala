package logicov.edgar

import java.time.LocalDate

case class XbrlFact(tag: String,
                    start: Option[LocalDate],
                    end: LocalDate,
                    value: BigDecimal,
                    form: String,
                    accession: String,
                    filed: LocalDate)
