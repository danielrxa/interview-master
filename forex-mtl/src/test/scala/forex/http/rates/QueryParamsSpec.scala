package forex.http.rates

import forex.domain.Currency
import org.scalatest.funsuite.AnyFunSuite

final class QueryParamsSpec extends AnyFunSuite {

  test("decode accepts supported currencies case-insensitively") {
    assert(QueryParams.decode("usd") == Right(Currency.USD))
  }

  test("decode rejects unsupported currencies with a descriptive error") {
    assert(QueryParams.decode("xxx") == Left("Unsupported currency: XXX"))
  }
}
