package logicov.publiccompany

import fabric.rw.*

case class PublicIssuer(cik: Long, ticker: String, name: String, sic: String, fiscalYearEnd: String) derives RW {
  def methodologyIssue: Option[String] = {
    if (sic.toIntOption.exists(code => code >= 6000 && code < 6800))
      Some("This issuer is classified as a financial or investment business. Its financials can be reviewed, but the current operating-company scorecard needs a separately agreed methodology before rating or memo generation.")
    else if (sic.toIntOption.isEmpty)
      Some("SEC industry classification is unavailable. Confirm an appropriate credit methodology with the reviewer before rating or memo generation; financials remain available for inspection.")
    else if (fiscalYearEnd != "1231")
      Some("This issuer does not use a December year-end. Review the cited periods; the current credit-profile workflow requires calendar fiscal years.")
    else None
  }
}
