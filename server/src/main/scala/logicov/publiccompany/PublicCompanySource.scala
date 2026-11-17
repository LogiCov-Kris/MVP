package logicov.publiccompany

import fabric.Json
import fabric.io.{JsonFormatter, JsonParser}
import fabric.rw.*
import logicov.edgar.EdgarClient

import java.time.LocalDate

class PublicCompanySource(client: EdgarClient) {
  def lookup(query: String): PublicIssuer = {
    val key = query.trim.toUpperCase
    require(key.matches("[A-Z0-9.-]{1,10}"), "Enter an exact ticker or a numeric SEC CIK.")
    val cik = if (key.forall(_.isDigit)) key.toLong else {
      val matches = client.tickers.asObj.value.values.filter(_("ticker").asString.equalsIgnoreCase(key)).toList
      require(matches.size == 1, "Ticker not found in the SEC directory. Try the issuer's numeric CIK.")
      matches.head("cik_str").asLong
    }
    require(cik > 0 && cik <= 9999999999L, "Invalid SEC CIK")
    issuer(cik, client.submissions(cik))
  }

  private def issuer(cik: Long, submissions: Json): PublicIssuer = {
    require(cik > 0 && cik <= 9999999999L, "Invalid SEC CIK")
    require(submissions("cik").asString.toLong == cik, "SEC issuer identity did not match the requested CIK")
    PublicIssuer(cik, submissions("tickers").asVector.headOption.fold("")(_.asString), submissions("name").asString,
      submissions.get("sic").fold("")(_.asString), submissions.get("fiscalYearEnd").fold("")(_.asString))
  }

  def fetch(cik: Long): PublicSnapshot = {
    val submissions = client.submissions(cik)
    val company = issuer(cik, submissions)
    val recent = submissions("filings")("recent")
    val forms = recent("form").asVector.map(_.asString)
    val annuals = forms.indices.filter(i => Set("10-K", "10-K/A").contains(forms(i)))
    require(annuals.nonEmpty, "This issuer has no recent 10-K. The current intake supports US annual and quarterly filings.")
    val annualEnd = annuals.map(i => LocalDate.parse(recent("reportDate").asVector(i).asString)).max
    val raw = client.companyFactsBytes(cik)
    val json = JsonParser(new String(raw, "UTF-8"))
    require(json("cik").asLong == cik, "SEC financial facts belong to a different issuer")
    val grid = PublicFinancials.grid(json, annualEnd)
    val cited = grid.values.flatMap(_.source.filing).toSet
    val indices = forms.indices.filter(i => cited.contains(recent("accessionNumber").asVector(i).asString)).toList
    require(indices.size <= 20, "This import references too many filings; use a reviewed source package.")
    var retainedSize = raw.length.toLong
    val documents = indices.map { i =>
      val accession = recent("accessionNumber").asVector(i).asString
      val primary = recent("primaryDocument").asVector(i).asString
      require(accession.matches("[0-9]{10}-[0-9]{2}-[0-9]{6}") && primary.matches("[A-Za-z0-9._-]+"), "Unexpected SEC filing path")
      val url = s"https://www.sec.gov/Archives/edgar/data/$cik/${accession.replace("-", "")}/$primary"
      val name = s"sec-$accession-$primary"
      val bytes = client.bytes(url)
      retainedSize += bytes.length
      require(retainedSize <= 190L * 1024 * 1024, "SEC filings exceed the source package limit; use a reviewed source package.")
      (name, "text/html", bytes, Map("name" -> name.json, "url" -> url.json, "accession" -> accession.json, "form" -> forms(i).json).json)
    }
    val manifest = Map("issuer" -> company.json, "retrievedAt" -> LocalDate.now().toString.json,
      "filings" -> documents.map(_._4).json,
      "companyFactsUrl" -> f"https://data.sec.gov/api/xbrl/companyfacts/CIK$cik%010d.json".json).json
    val sources = List(("sec-companyfacts.json", "application/json", raw),
      ("sec-submissions.json", "application/json", JsonFormatter.Compact(submissions).getBytes("UTF-8")),
      ("sec-source-manifest.json", "application/json", JsonFormatter.Default(manifest).getBytes("UTF-8"))) ++ documents.map(d => (d._1, d._2, d._3))
    require(sources.map(_._3.length.toLong).sum <= 200L * 1024 * 1024, "SEC source package exceeds the 200 MB limit")
    PublicSnapshot(company, grid, sources)
  }
}
