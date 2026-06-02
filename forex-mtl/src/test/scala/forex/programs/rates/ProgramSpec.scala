package forex.programs.rates

import cats.effect.IO
import forex.domain.{ Currency, Price, Rate, Timestamp }
import forex.services.rates.{ Algebra => RatesService }
import forex.services.rates.errors.{ Error => ServiceError }
import org.scalatest.funsuite.AnyFunSuite

final class ProgramSpec extends AnyFunSuite {

  private val request = Protocol.GetRatesRequest(Currency.USD, Currency.JPY)
  private val rate    = Rate(Rate.Pair(Currency.USD, Currency.JPY), Price(BigDecimal("0.71")), Timestamp.now)

  test("returns a rate from the service") {
    assert(program(Right(rate)).get(request).unsafeRunSync() == Right(rate))
  }

  test("maps lookup failures") {
    val result = program(Left(ServiceError.OneFrameLookupFailed("failure"))).get(request).unsafeRunSync()

    assert(result == Left(errors.Error.RateLookupFailed("failure")))
  }

  test("maps unavailable rates") {
    val result = program(Left(ServiceError.RateUnavailable("missing"))).get(request).unsafeRunSync()

    assert(result == Left(errors.Error.RateUnavailable("missing")))
  }

  test("maps unsupported pairs") {
    val result = program(Left(ServiceError.UnsupportedPair("invalid"))).get(request).unsafeRunSync()

    assert(result == Left(errors.Error.UnsupportedPair("invalid")))
  }

  private def program(result: Either[ServiceError, Rate]): Program[IO] = {
    val service = new RatesService[IO] {
      override def get(_pair: Rate.Pair): IO[Either[ServiceError, Rate]] =
        IO.pure(result)
    }
    new Program[IO](service)
  }
}
