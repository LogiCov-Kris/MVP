package logicov.analysis

import fabric.rw.*
import logicov.source.Source

case class ProForma(event: String,
                    cashChange: BigDecimal,
                    debtChange: BigDecimal,
                    ebitda: Option[BigDecimal],
                    source: Source) derives RW
