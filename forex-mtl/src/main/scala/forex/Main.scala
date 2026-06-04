package forex

import scala.concurrent.ExecutionContext
import cats.effect._
import cats.syntax.either._
import dev.profunktor.redis4cats.connection.{ RedisClient, RedisURI => Redis4CatsURI }
import dev.profunktor.redis4cats.domain.RedisCodec
import dev.profunktor.redis4cats.effect.Log
import dev.profunktor.redis4cats.interpreter.Redis
import forex.config._
import forex.services.rates.cache.RedisStringCache
import forex.services.rates.RatesRefresher
import forex.services.rates.interpreters.{ OneFrameLive, RedisRatesCache }
import fs2.Stream
import org.http4s.Uri
import org.http4s.blaze.client.BlazeClientBuilder
import org.http4s.blaze.server.BlazeServerBuilder

object Main extends IOApp {

  override def run(args: List[String]): IO[ExitCode] =
    new Application[IO].stream(executionContext).compile.drain.as(ExitCode.Success)

}

class Application[F[_]: ConcurrentEffect: ContextShift: Timer] {

  private implicit val redisLog: Log[F] = new Log[F] {
    override def info(msg: => String): F[Unit]  = Sync[F].unit
    override def error(msg: => String): F[Unit] = Sync[F].unit
  }

  def stream(ec: ExecutionContext): Stream[F, Unit] =
    for {
      config <- Config.stream("app")
      _ <- Stream.resource(BlazeClientBuilder[F](ec).resource).flatMap { client =>
            Stream.resource(redis(config.redis.uri)).flatMap { redis =>
              Stream.eval(parseUri(config.oneFrame.baseUri)).flatMap { baseUri =>
                val oneFrame = new OneFrameLive[F](client, baseUri, config.oneFrame.token)
                val cache = new RedisStringCache[F](redis)
                val ratesService = new RedisRatesCache[F](
                  oneFrame,
                  cache,
                  config.redis.keyPrefix,
                  config.oneFrame.maxRateAge,
                  config.redis.refreshLockTtl,
                  Sync[F].delay(java.time.OffsetDateTime.now)
                )
                val module = new Module[F](config, ratesService)
                val refresh =
                  new RatesRefresher[F](ratesService.refresh, config.oneFrame.refreshInterval).stream

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

  private def redis(uri: String) =
    for {
      parsedUri   <- Resource.eval(Redis4CatsURI.make[F](uri))
      redisClient <- RedisClient[F](parsedUri)
      redis       <- Redis[F, String, String](redisClient, RedisCodec.Utf8, parsedUri)
    } yield redis

  private def parseUri(value: String): F[Uri] =
    Sync[F].fromEither(
      Uri.fromString(value).leftMap(error => new IllegalArgumentException(error.message))
    )
}
