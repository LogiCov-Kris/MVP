package logicov.memo

import fabric.Json
import fabric.io.JsonParser
import logicov.edgar.OfficerSection

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Duration

case class PublicCompany private[memo](name: String, industry: String, officers: Option[String], source: String)

object PublicCompany {
  def load(cik: Long): PublicCompany = {
    require(cik > 0 && cik <= 9999999999L, "A public SEC issuer is required")
    val contact = sys.env.getOrElse("EDGAR_USER_AGENT", throw new IllegalStateException("Configure the SEC contact before enabling public narratives"))
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    def read(url: String): String = {
      val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("User-Agent", contact).build()
      val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
      val stream = response.body()
      try {
        require(response.statusCode() == 200, s"Public SEC source returned ${response.statusCode()}")
        val bytes = stream.readNBytes(20 * 1024 * 1024 + 1)
        require(bytes.length <= 20 * 1024 * 1024, "Public SEC source exceeds the supported size")
        new String(bytes, StandardCharsets.UTF_8)
      } finally stream.close()
    }
    try {
      val url = f"https://data.sec.gov/submissions/CIK$cik%010d.json"
      fromSubmissions(JsonParser(read(url)), cik, read)
    } finally client.close()
  }

  private[memo] def fromSubmissions(data: Json, cik: Long, read: String => String): PublicCompany = {
    require(data("cik").asString.toLong == cik, "Public issuer identifier does not match")
    val name = data("name").asString
    val industry = data("sicDescription").asString
    val recent = data("filings")("recent")
    val annual = recent("form").asVector.indexWhere(_.asString == "10-K")
    val filing = Option.when(annual >= 0) {
      val accession = recent("accessionNumber").asVector(annual).asString.replace("-", "")
      val document = recent("primaryDocument").asVector(annual).asString
      require(accession.matches("[0-9]{18}") && document.matches("[A-Za-z0-9_-][A-Za-z0-9_.-]*"), "Invalid public filing location")
      s"https://www.sec.gov/Archives/edgar/data/$cik/$accession/$document"
    }
    PublicCompany(name, industry, filing.flatMap(url => OfficerSection.find(read(url))),
      filing.getOrElse(f"https://data.sec.gov/submissions/CIK$cik%010d.json"))
  }
}
