package forex

import scala.concurrent.ExecutionContext
import cats.effect._
import cats.syntax.either._
import forex.config._
import forex.services.rates.RatesRefresher
import forex.services.rates.interpreters.{ CachedRates, OneFrameLive }
import fs2.Stream
import org.http4s.Uri
import org.http4s.blaze.client.BlazeClientBuilder
import org.http4s.blaze.server.BlazeServerBuilder

object Main extends IOApp {

  override def run(args: List[String]): IO[ExitCode] =
    new Application[IO].stream(executionContext).compile.drain.as(ExitCode.Success)

}

class Application[F[_]: ConcurrentEffect: Timer] {

  def stream(ec: ExecutionContext): Stream[F, Unit] =
    for {
      config <- Config.stream("app")
      _ <- Stream.resource(BlazeClientBuilder[F](ec).resource).flatMap { client =>
            Stream.eval(parseUri(config.oneFrame.baseUri)).flatMap { baseUri =>
              val oneFrame = new OneFrameLive[F](client, baseUri, config.oneFrame.token)
              Stream.eval(CachedRates.create[F](oneFrame, config.oneFrame.maxRateAge)).flatMap { ratesService =>
                val module = new Module[F](config, ratesService)
                val refresh = new RatesRefresher[F](
                  ratesService.refresh,
                  config.oneFrame.refreshInterval
                ).stream

                BlazeServerBuilder[F](ec)
                  .bindHttp(config.http.port, config.http.host)
                  .withHttpApp(module.httpApp)
                  .serve
                  .concurrently(refresh)
                  .map(_ => ())
              }
            }
          }
    } yield ()

  private def parseUri(value: String): F[Uri] =
    Sync[F].fromEither(
      Uri.fromString(value).leftMap(error => new IllegalArgumentException(error.message))
    )
}
