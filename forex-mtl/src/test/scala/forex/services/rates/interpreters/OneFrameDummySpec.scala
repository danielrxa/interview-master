package forex.services.rates.interpreters

import cats.effect.IO
import forex.domain.{ Currency, Price, Rate }
import org.scalatest.funsuite.AnyFunSuite

final class OneFrameDummySpec extends AnyFunSuite {

  test("returns the dummy rate for the requested pair") {
    val pair = Rate.Pair(Currency.USD, Currency.JPY)
    val rate = new OneFrameDummy[IO].get(pair).unsafeRunSync().toOption.get

    assert(rate.pair == pair)
    assert(rate.price == Price(BigDecimal(100)))
  }
}
