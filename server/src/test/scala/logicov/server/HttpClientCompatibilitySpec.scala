package logicov.server

import io.netty.handler.codec.http.{DefaultHttpRequest, HttpHeaderNames, HttpHeaderValues, HttpMethod, HttpUtil, HttpVersion}
import org.scalatest.funsuite.AnyFunSuite

class HttpClientCompatibilitySpec extends AnyFunSuite {
  test("outbound HTTP headers use a compatible codec implementation") {
    val request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/messages")
    request.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED)
    assert(HttpUtil.isTransferEncodingChunked(request))
    assert(request.headers().containsValue(HttpHeaderNames.TRANSFER_ENCODING, "CHUNKED", true))
  }
}
