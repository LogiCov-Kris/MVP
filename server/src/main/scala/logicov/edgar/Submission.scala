package logicov.edgar

import java.time.LocalDate

case class Submission(form: String, accession: String, filed: LocalDate, reportDate: LocalDate, primary: String, items: String)
