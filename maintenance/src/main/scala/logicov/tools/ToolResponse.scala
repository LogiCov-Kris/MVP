package logicov.tools

import fabric.Json
import fabric.io.JsonParser
import java.net.http.HttpResponse
import scala.jdk.CollectionConverters.*

case class ToolResponse(response: HttpResponse[Array[Byte]]) {
  def status: Int = response.statusCode()
  def bytes: Array[Byte] = response.body()
  def text: String = new String(bytes, "UTF-8")
  def json: Json = JsonParser(text)
  def header(name: String): String = response.headers().allValues(name).asScala.mkString("; ")
  def expect(code: Int): ToolResponse = { require(status == code, s"Expected HTTP $code, received $status"); this }
}
