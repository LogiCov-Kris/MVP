package logicov.edgar

import fabric.rw.*
import logicov.model.JsonFormats.given

import java.time.LocalDate

case class PackFiling(role: FilingRole, form: String, accession: String, filed: LocalDate, periodEnd: LocalDate, file: String, url: String) derives RW
