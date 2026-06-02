package forex.http.rates

import cats.effect.IO
import forex.domain.{ Currency, Price, Rate, Timestamp }
import forex.programs.rates.{ Algebra, Protocol => ProgramProtocol }
import forex.programs.rates.errors.Error
import io.circe.parser.parse
import org.http4s.{ Request, Status, Uri }
import org.http4s.implicits._
import org.scalatest.funsuite.AnyFunSuite

final class RatesHttpRoutesSpec extends AnyFunSuite {

  private val rate = Rate(
    Rate.Pair(Currency.USD, Currency.JPY),
    Price(BigDecimal("0.71")),
    Timestamp(java.time.OffsetDateTime.parse("2026-05-31T12:00:00Z"))
  )

  test("returns a rate as JSON") {
    val response = run(Right(rate), "/rates?from=USD&to=JPY")

    assert(response._1 == Status.Ok)
    assert(response._2.hcursor.get[String]("from") == Right("USD"))
    assert(response._2.hcursor.get[String]("to") == Right("JPY"))
    assert(response._2.hcursor.get[BigDecimal]("price") == Right(BigDecimal("0.71")))
  }

  test("returns bad request for an unsupported currency") {
    val response = run(Right(rate), "/rates?from=XXX&to=JPY")

    assert(response._1 == Status.BadRequest)
    assert(response._2.hcursor.get[String]("message") == Right("Unsupported currency: XXX"))
  }

  test("returns bad request for a missing query parameter") {
    val response = run(Right(rate), "/rates?from=USD")

    assert(response._1 == Status.BadRequest)
    assert(response._2.hcursor.get[String]("message") == Right("Missing query parameter: to"))
  }

  test("returns bad request for an unsupported pair") {
    val response = run(Left(Error.UnsupportedPair("invalid pair")), "/rates?from=USD&to=USD")

    assert(response._1 == Status.BadRequest)
    assert(response._2.hcursor.get[String]("message") == Right("invalid pair"))
  }

  test("returns service unavailable when no fresh rate exists") {
    val response = run(Left(Error.RateUnavailable("missing rate")), "/rates?from=USD&to=JPY")

    assert(response._1 == Status.ServiceUnavailable)
    assert(response._2.hcursor.get[String]("message") == Right("missing rate"))
  }

  test("returns bad gateway when lookup fails") {
    val response = run(Left(Error.RateLookupFailed("provider failure")), "/rates?from=USD&to=JPY")

    assert(response._1 == Status.BadGateway)
    assert(response._2.hcursor.get[String]("message") == Right("provider failure"))
  }

  private def run(result: Either[Error, Rate], path: String): (Status, io.circe.Json) = {
    val program = new Algebra[IO] {
      override def get(_request: ProgramProtocol.GetRatesRequest): IO[Either[Error, Rate]] =
        IO.pure(result)
    }
    val request  = Request[IO](uri = Uri.unsafeFromString(path))
    val response = new RatesHttpRoutes[IO](program).routes.orNotFound.run(request).unsafeRunSync()
    val json     = response.as[String].map(parse(_).toOption.get).unsafeRunSync()

    response.status -> json
  }
}
