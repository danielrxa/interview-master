package forex.services.rates.interpreters

import java.time.OffsetDateTime
import scala.concurrent.duration.FiniteDuration
import cats.effect.Sync
import cats.syntax.applicative._
import cats.syntax.apply._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._
import forex.domain.{ Currency, Price, Rate, Timestamp }
import forex.observability.{ SpanAttribute, Tracing }
import forex.services.rates.Algebra
import forex.services.rates.cache.StringCache
import forex.services.rates.errors.Error
import forex.services.rates.oneframe.{ Algebra => OneFrame }
import io.opentelemetry.api.trace.SpanKind
import io.circe.{ Decoder, Encoder }
import io.circe.parser.decode
import io.circe.syntax._
import org.slf4j.LoggerFactory

final class RedisRatesCache[F[_]: Sync](
    oneFrame: OneFrame[F],
    cache: StringCache[F],
    keyPrefix: String,
    maxRateAge: FiniteDuration,
    now: F[OffsetDateTime],
    tracing: Tracing[F] = Tracing.noop[F]
) extends Algebra[F] {

  import RedisRatesCache._

  private val logger = LoggerFactory.getLogger(getClass)

  override def get(pair: Rate.Pair): F[Either[Error, Rate]] = {
    val lookup: F[Either[Error, Rate]] =
      if (pair.from == pair.to)
        (Error.UnsupportedPair("The currencies in a pair must be different"): Error).asLeft[Rate].pure[F]
      else
        now.flatMap { currentTime =>
          cache.get(key(pair)).map { cachedValue =>
            val result = cachedValue
              .toRight(Error.RateUnavailable(s"No fresh rate is available for $pair"))
              .flatMap { payload =>
                decode[StoredRate](payload)
                  .leftMap(error => Error.RateUnavailable(s"Cached rate is invalid for $pair: ${error.getMessage}"))
                  .map(_.asRate)
                  .filterOrElse(
                    rate => !rate.timestamp.value.plusNanos(maxRateAge.toNanos).isBefore(currentTime),
                    Error.RateUnavailable(s"No fresh rate is available for $pair")
                  )
              }

            logGet(pair, result)
            result
          }
        }

    tracing.spanWithResult(
      name = "rates.cache.get",
      kind = SpanKind.INTERNAL,
      attributes = pairAttributes(pair)
    )(lookup)(
      result => List(SpanAttribute("cache.result", result.fold(_ => "miss", _ => "hit"))),
      _.left.toOption.map(describe)
    )
  }

  def refresh: F[Either[Error, Unit]] =
    tracing.spanWithResult(
      name = "rates.cache.refresh",
      kind = SpanKind.INTERNAL,
      attributes = List(
        SpanAttribute("forex.pair.count", Currency.pairs.size.toString),
        SpanAttribute("cache.key_prefix", keyPrefix)
      )
    )(
      Sync[F].delay(logger.info("Refreshing Redis rates cache for {} currency pairs", Currency.pairs.size)) *>
      oneFrame.get(Currency.pairs).flatMap {
        case Left(error) =>
          Sync[F]
            .delay(logger.warn("Redis rates cache refresh failed: {}", describe(error)))
            .as(error.asLeft[Unit])
        case Right(rates) =>
          rates
            .traverse(rate => cache.setEx(key(rate.pair), StoredRate.fromRate(rate).asJson.noSpaces, maxRateAge))
            .flatTap(_ => Sync[F].delay(logger.info("Redis rates cache refreshed with {} rates", rates.size)))
            .as(().asRight[Error])
      }
    )(
      result => List(SpanAttribute("refresh.result", result.fold(_ => "failure", _ => "success"))),
      _.left.toOption.map(describe)
    )

  private def key(pair: Rate.Pair): String =
    s"$keyPrefix:${Currency.show.show(pair.from)}${Currency.show.show(pair.to)}"

  private def pairAttributes(pair: Rate.Pair): List[SpanAttribute] =
    List(
      SpanAttribute("forex.from", Currency.show.show(pair.from)),
      SpanAttribute("forex.to", Currency.show.show(pair.to))
    )

  private def logGet(pair: Rate.Pair, result: Either[Error, Rate]): Unit =
    result match {
      case Right(rate) =>
        logger.info(
          "Redis rates cache hit for {}{} price={} timestamp={}",
          Currency.show.show(pair.from),
          Currency.show.show(pair.to),
          rate.price.value.bigDecimal,
          rate.timestamp.value
        )
      case Left(error) =>
        logger.warn(
          "Redis rates cache lookup failed for {}{}: {}",
          Currency.show.show(pair.from),
          Currency.show.show(pair.to),
          describe(error)
        )
    }

  private def describe(error: Error): String =
    error match {
      case Error.OneFrameLookupFailed(msg) => s"one-frame lookup failed: $msg"
      case Error.RateUnavailable(msg)      => s"rate unavailable: $msg"
      case Error.UnsupportedPair(msg)      => s"unsupported pair: $msg"
    }
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
