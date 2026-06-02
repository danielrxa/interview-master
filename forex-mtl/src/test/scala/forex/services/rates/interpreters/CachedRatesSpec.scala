package forex.services.rates.interpreters

import java.time.OffsetDateTime
import scala.concurrent.duration._
import cats.effect.IO
import cats.effect.concurrent.Ref
import forex.domain.{ Currency, Price, Rate, Timestamp }
import forex.services.rates.errors.Error
import forex.services.rates.oneframe.Algebra
import org.scalatest.funsuite.AnyFunSuite

final class CachedRatesSpec extends AnyFunSuite {

  private val pair = Rate.Pair(Currency.USD, Currency.JPY)
  private val now  = OffsetDateTime.parse("2026-05-31T12:00:00Z")

  test("returns a fresh cached rate") {
    val rate = createRate(now.minusMinutes(4))

    assert(service(Map(pair -> rate)).flatMap(_.get(pair)).unsafeRunSync() == Right(rate))
  }

  test("returns a rate whose age is exactly the configured limit") {
    val rate = createRate(now.minusMinutes(5))

    assert(service(Map(pair -> rate)).flatMap(_.get(pair)).unsafeRunSync() == Right(rate))
  }

  test("rejects an expired cached rate") {
    val rate = createRate(now.minusMinutes(5).minusNanos(1L))

    assert(service(Map(pair -> rate)).flatMap(_.get(pair)).unsafeRunSync().left.toOption.exists(
      _.isInstanceOf[Error.RateUnavailable]
    ))
  }

  test("rejects a missing cached rate") {
    assert(service(Map.empty).flatMap(_.get(pair)).unsafeRunSync().left.toOption.exists(
      _.isInstanceOf[Error.RateUnavailable]
    ))
  }

  test("rejects pairs containing the same currency") {
    val invalidPair = Rate.Pair(Currency.USD, Currency.USD)

    assert(service(Map.empty).flatMap(_.get(invalidPair)).unsafeRunSync().left.toOption.exists(
      _.isInstanceOf[Error.UnsupportedPair]
    ))
  }

  test("refresh requests every supported pair and atomically replaces the cache") {
    val oldRate = createRate(now.minusMinutes(1))
    val newRate = Rate(pair, Price(BigDecimal("0.72")), Timestamp(now))
    val result = for {
      requested <- Ref.of[IO, List[Rate.Pair]](List.empty)
      cache     <- Ref.of[IO, Map[Rate.Pair, Rate]](Map(Rate.Pair(Currency.EUR, Currency.CHF) -> oldRate))
      oneFrame = new Algebra[IO] {
        override def get(pairs: List[Rate.Pair]): IO[Either[Error, List[Rate]]] =
          requested.set(pairs).as(Right(List(newRate)))
      }
      rates = new CachedRates[IO](oneFrame, cache, 5.minutes, IO.pure(now))
      refreshed <- rates.refresh
      pairs     <- requested.get
      stored    <- cache.get
    } yield (refreshed, pairs, stored)

    val (refreshed, pairs, stored) = result.unsafeRunSync()

    assert(refreshed == Right(()))
    assert(pairs == Currency.pairs)
    assert(stored == Map(pair -> newRate))
  }

  test("refresh failure preserves the previous cache") {
    val rate = createRate(now.minusMinutes(1))
    val failure = Error.OneFrameLookupFailed("provider unavailable")
    val result = for {
      cache <- Ref.of[IO, Map[Rate.Pair, Rate]](Map(pair -> rate))
      oneFrame = new Algebra[IO] {
        override def get(_pairs: List[Rate.Pair]): IO[Either[Error, List[Rate]]] =
          IO.pure(Left(failure))
      }
      rates = new CachedRates[IO](oneFrame, cache, 5.minutes, IO.pure(now))
      refreshed <- rates.refresh
      stored    <- cache.get
    } yield (refreshed, stored)

    assert(result.unsafeRunSync() == (Left(failure), Map(pair -> rate)))
  }

  test("create starts with an empty cache") {
    val oneFrame = new Algebra[IO] {
      override def get(_pairs: List[Rate.Pair]): IO[Either[Error, List[Rate]]] =
        IO.pure(Right(List.empty))
    }

    assert(CachedRates.create[IO](oneFrame, 5.minutes).flatMap(_.get(pair)).unsafeRunSync().isLeft)
  }

  private def service(initial: Map[Rate.Pair, Rate]): IO[CachedRates[IO]] =
    Ref.of[IO, Map[Rate.Pair, Rate]](initial).map { cache =>
      val oneFrame = new Algebra[IO] {
        override def get(_pairs: List[Rate.Pair]): IO[Either[Error, List[Rate]]] =
          IO.pure(Right(List.empty))
      }
      new CachedRates[IO](oneFrame, cache, 5.minutes, IO.pure(now))
    }

  private def createRate(timestamp: OffsetDateTime): Rate =
    Rate(pair, Price(BigDecimal("0.71")), Timestamp(timestamp))
}
