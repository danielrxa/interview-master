package forex.domain

import org.scalatest.funsuite.AnyFunSuite

final class CurrencySpec extends AnyFunSuite {

  test("fromString parses supported currencies case-insensitively") {
    assert(Currency.fromString("usd") == Right(Currency.USD))
    assert(Currency.fromString("JPY") == Right(Currency.JPY))
  }

  test("fromString rejects unsupported currencies") {
    assert(Currency.fromString("xxx") == Left("Unsupported currency: XXX"))
  }

  test("pairs contains every directed pair of different supported currencies") {
    assert(Currency.all.size == 9)
    assert(Currency.pairs.size == 72)
    assert(Currency.pairs.distinct.size == 72)
    assert(!Currency.pairs.exists(pair => pair.from == pair.to))
    assert(Currency.pairs.contains(Rate.Pair(Currency.USD, Currency.JPY)))
    assert(Currency.pairs.contains(Rate.Pair(Currency.JPY, Currency.USD)))
  }
}
