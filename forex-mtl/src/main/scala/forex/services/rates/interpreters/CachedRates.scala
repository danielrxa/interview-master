package forex.services.rates.interpreters

import java.time.OffsetDateTime
import scala.concurrent.duration.FiniteDuration
import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.syntax.functor._
import cats.syntax.flatMap._
import cats.syntax.either._
import cats.syntax.applicative._
import forex.domain.{ Currency, Rate }
import forex.services.rates.Algebra
import forex.services.rates.errors.Error
import forex.services.rates.oneframe.{ Algebra => OneFrame }

final class CachedRates[F[_]: Sync](
    oneFrame: OneFrame[F],
    cache: Ref[F, Map[Rate.Pair, Rate]],
    maxRateAge: FiniteDuration,
    now: F[OffsetDateTime]
) extends Algebra[F] {

  override def get(pair: Rate.Pair): F[Either[Error, Rate]] =
    if (pair.from == pair.to)
      (Error.UnsupportedPair("The currencies in a pair must be different"): Error).asLeft[Rate].pure[F]
    else
      now.flatMap { currentTime =>
        cache.get.map(
          _.get(pair)
            .filter(rate => !rate.timestamp.value.plusNanos(maxRateAge.toNanos).isBefore(currentTime))
            .toRight(Error.RateUnavailable(s"No fresh rate is available for $pair"))
        )
      }

  def refresh: F[Either[Error, Unit]] =
    oneFrame.get(Currency.pairs).flatMap {
      case Left(error)  => error.asLeft[Unit].pure[F]
      case Right(rates) => cache.set(rates.map(rate => rate.pair -> rate).toMap).as(().asRight[Error])
    }
}

object CachedRates {
  def create[F[_]: Sync](
      oneFrame: OneFrame[F],
      maxRateAge: FiniteDuration
  ): F[CachedRates[F]] =
    Ref
      .of[F, Map[Rate.Pair, Rate]](Map.empty)
      .map(new CachedRates[F](oneFrame, _, maxRateAge, Sync[F].delay(OffsetDateTime.now)))
}
