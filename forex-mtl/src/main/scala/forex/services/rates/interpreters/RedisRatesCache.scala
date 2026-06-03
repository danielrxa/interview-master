package forex.services.rates.interpreters

import java.time.OffsetDateTime
import scala.concurrent.duration.FiniteDuration
import cats.effect.Sync
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._
import forex.domain.{ Currency, Price, Rate, Timestamp }
import forex.services.rates.Algebra
import forex.services.rates.cache.StringCache
import forex.services.rates.errors.Error
import forex.services.rates.oneframe.{ Algebra => OneFrame }
import io.circe.{ Decoder, Encoder }
import io.circe.parser.decode
import io.circe.syntax._

final class RedisRatesCache[F[_]: Sync](
    oneFrame: OneFrame[F],
    cache: StringCache[F],
    keyPrefix: String,
    maxRateAge: FiniteDuration,
    now: F[OffsetDateTime]
) extends Algebra[F] {

  import RedisRatesCache._

  override def get(pair: Rate.Pair): F[Either[Error, Rate]] =
    if (pair.from == pair.to)
      (Error.UnsupportedPair("The currencies in a pair must be different"): Error).asLeft[Rate].pure[F]
    else
      now.flatMap { currentTime =>
        cache.get(key(pair)).map {
          _.toRight(Error.RateUnavailable(s"No fresh rate is available for $pair")).flatMap { payload =>
            decode[StoredRate](payload)
              .leftMap(error => Error.RateUnavailable(s"Cached rate is invalid for $pair: ${error.getMessage}"))
              .map(_.asRate)
              .filterOrElse(
                rate => !rate.timestamp.value.plusNanos(maxRateAge.toNanos).isBefore(currentTime),
                Error.RateUnavailable(s"No fresh rate is available for $pair")
              )
          }
        }
      }

  def refresh: F[Either[Error, Unit]] =
    oneFrame.get(Currency.pairs).flatMap {
      case Left(error) => error.asLeft[Unit].pure[F]
      case Right(rates) =>
        rates
          .traverse(rate => cache.setEx(key(rate.pair), StoredRate.fromRate(rate).asJson.noSpaces, maxRateAge))
          .as(().asRight[Error])
    }

  private def key(pair: Rate.Pair): String =
    s"$keyPrefix:${Currency.show.show(pair.from)}${Currency.show.show(pair.to)}"
}

object RedisRatesCache {

  final case class StoredRate(
      from: Currency,
      to: Currency,
      price: BigDecimal,
      timestamp: OffsetDateTime
  ) {
    def asRate: Rate =
      Rate(Rate.Pair(from, to), Price(price), Timestamp(timestamp))
  }

  object StoredRate {
    def fromRate(rate: Rate): StoredRate =
      StoredRate(rate.pair.from, rate.pair.to, rate.price.value, rate.timestamp.value)
  }

  implicit val currencyEncoder: Encoder[Currency] =
    Encoder.encodeString.contramap(Currency.show.show)

  implicit val currencyDecoder: Decoder[Currency] =
    Decoder.decodeString.emap(Currency.fromString)

  implicit val timestampEncoder: Encoder[OffsetDateTime] =
    Encoder.encodeString.contramap(_.toString)

  implicit val timestampDecoder: Decoder[OffsetDateTime] =
    Decoder.decodeString.emap(value =>
      Either.catchNonFatal(OffsetDateTime.parse(value)).leftMap(_.getMessage)
    )

  implicit val storedRateEncoder: Encoder[StoredRate] =
    Encoder.forProduct4("from", "to", "price", "timestamp")(rate =>
      (rate.from, rate.to, rate.price, rate.timestamp)
    )

  implicit val storedRateDecoder: Decoder[StoredRate] =
    Decoder.forProduct4("from", "to", "price", "timestamp")(StoredRate.apply)
}
