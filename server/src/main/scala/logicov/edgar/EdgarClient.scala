package logicov.edgar

import fabric.Json
import fabric.io.JsonParser

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}

class EdgarClient(userAgent: String) {
  private val client = HttpClient.newHttpClient()
  private val Attempts = 4

  def companyFacts(cik: Long): CompanyFacts = CompanyFacts.parse(JsonParser(new String(companyFactsBytes(cik), "UTF-8")))

  def companyFactsBytes(cik: Long): Array[Byte] = bytes(f"https://data.sec.gov/api/xbrl/companyfacts/CIK$cik%010d.json")

  def submissions(cik: Long): Json = json(f"https://data.sec.gov/submissions/CIK$cik%010d.json")

  def tickers: Json = json("https://www.sec.gov/files/company_tickers.json")

  def json(url: String): Json = JsonParser(new String(bytes(url), "UTF-8"))

  def bytes(url: String): Array[Byte] = attempt(url, 1)

  private def attempt(url: String, number: Int): Array[Byte] = {
    Thread.sleep(150L * number)
    val request = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", userAgent).build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
    response.statusCode() match {
      case 200 => response.body()
      case 429 | 503 if number < Attempts => attempt(url, number + 1)
      case status => throw new RuntimeException(s"EDGAR returned $status for $url")
    }
  }
}
