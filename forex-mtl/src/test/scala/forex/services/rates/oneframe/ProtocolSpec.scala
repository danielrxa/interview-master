package forex.services.rates.oneframe

import forex.domain.{ Currency, Price, Rate }
import io.circe.parser.decode
import org.scalatest.funsuite.AnyFunSuite

final class ProtocolSpec extends AnyFunSuite {

  import Protocol._

  test("decodes a One-Frame response and assumes its timestamp is UTC") {
    val json =
      """{"from":"USD","to":"JPY","bid":0.61,"ask":0.82,"price":0.71,"time_stamp":"2019-01-01T00:00:00.000"}"""

    val response = decode[OneFrameResponse](json).toOption.get

    assert(response.asRate.pair == Rate.Pair(Currency.USD, Currency.JPY))
    assert(response.asRate.price == Price(BigDecimal("0.71")))
    assert(response.asRate.timestamp.value.toString == "2019-01-01T00:00Z")
  }

  test("rejects unsupported currencies") {
    val json = """{"from":"XXX","to":"JPY","price":0.71,"time_stamp":"2019-01-01T00:00:00.000"}"""

    assert(decode[OneFrameResponse](json).isLeft)
  }

  test("rejects malformed timestamps") {
    val json = """{"from":"USD","to":"JPY","price":0.71,"time_stamp":"not-a-timestamp"}"""

    assert(decode[OneFrameResponse](json).isLeft)
  }

  test("rejects responses without a price") {
    val json = """{"from":"USD","to":"JPY","time_stamp":"2019-01-01T00:00:00.000"}"""

    assert(decode[OneFrameResponse](json).isLeft)
  }
}
