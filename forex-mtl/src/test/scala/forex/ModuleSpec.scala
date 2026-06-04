package forex

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import cats.effect.{ ContextShift, IO, Timer }
import forex.config.{ ApplicationConfig, HttpConfig, ObservabilityConfig, OneFrameConfig, RedisConfig }
import forex.domain.{ Currency, Price, Rate, Timestamp }
import forex.services.rates.{ Algebra, errors }
import org.http4s.{ Request, Status, Uri }
import org.scalatest.funsuite.AnyFunSuite

final class ModuleSpec extends AnyFunSuite {

  private implicit val contextShift: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  private implicit val timer: Timer[IO]               = IO.timer(ExecutionContext.global)

  test("exposes rates routes through the configured HTTP application") {
    val pair = Rate.Pair(Currency.USD, Currency.JPY)
    val rate = Rate(pair, Price(BigDecimal("0.71")), Timestamp.now)
    val service = new Algebra[IO] {
      override def get(_pair: Rate.Pair): IO[Either[errors.Error, Rate]] =
        IO.pure(Right(rate))
    }
    val request  = Request[IO](uri = Uri.unsafeFromString("/rates/?from=USD&to=JPY"))
    val response = new Module[IO](config, service).httpApp.run(request).unsafeRunSync()

    assert(response.status == Status.Ok)
  }

  private val config = ApplicationConfig(
    http = HttpConfig("0.0.0.0", 8080, 1.second),
    oneFrame = OneFrameConfig("http://localhost:8080", "token", 4.minutes, 5.minutes),
    redis = RedisConfig("redis://localhost:6379", "forex:rates"),
    observability = ObservabilityConfig(enabled = false, serviceName = "forex-mtl", otlpEndpoint = "http://localhost:4317")
  )
}
