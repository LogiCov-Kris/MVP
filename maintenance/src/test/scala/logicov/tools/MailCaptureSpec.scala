package logicov.tools

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MailCaptureSpec extends AnyFlatSpec with Matchers {
  "Mail capture" should "preserve multipart sign-in content and UTF-8 text" in {
    val body = "--boundary\r\nContent-Disposition: form-data; name=\"to\"\r\n\r\nanalyst@example.test\r\n--boundary\r\nContent-Disposition: form-data; name=\"text\"\r\nContent-Type: text/plain; charset=utf-8\r\n\r\nSign in: https://example.test/#token=example\nRésumé\r\n--boundary--\r\n"
    val fields = MailCapture.fields("multipart/form-data; boundary=\"boundary\"", body.getBytes("UTF-8"))
    fields("to") should be("analyst@example.test")
    fields("text") should be("Sign in: https://example.test/#token=example\nRésumé")
  }
  it should "reject oversized, incomplete and non-multipart messages" in {
    intercept[IllegalArgumentException](MailCapture.fields("multipart/form-data; boundary=a", Array.fill[Byte](65537)(0)))
    intercept[IllegalArgumentException](MailCapture.fields("multipart/form-data; boundary=a", "--a\r\n".getBytes("UTF-8")))
    intercept[IllegalArgumentException](MailCapture.fields("application/json", "{}".getBytes("UTF-8")))
  }
}
