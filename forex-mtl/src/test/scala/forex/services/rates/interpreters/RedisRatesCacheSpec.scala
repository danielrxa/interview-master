package forex.services.rates.interpreters

import java.time.OffsetDateTime
import scala.concurrent.duration._
import cats.effect.IO
import cats.effect.concurrent.Ref
import forex.domain.{ Currency, Price, Rate, Timestamp }
import forex.services.rates.cache.StringCache
import forex.services.rates.errors.Error
import forex.services.rates.oneframe.{ Algebra => OneFrame }
import org.scalatest.funsuite.AnyFunSuite

final class RedisRatesCacheSpec extends AnyFunSuite {

  private val pair      = Rate.Pair(Currency.USD, Currency.JPY)
  private val now       = OffsetDateTime.parse("2026-06-03T12:00:00Z")
  private val maxAge    = 5.minutes
  private val lockTtl   = 30.seconds
  private val keyPrefix = "forex:test"
  private val key       = "forex:test:USDJPY"
  private val lockKey   = "forex:test:refresh-lock"

  test("returns a fresh Redis cached rate") {
    val rate = createRate(now.minusMinutes(1))
    val result = for {
      cache <- FakeStringCache.create(Map(key -> StoredValue(payload(rate), maxAge)))
      rates = redisRates(cache, provider(Right(List.empty)))
      found <- rates.get(pair)
    } yield found

    assert(result.unsafeRunSync() == Right(rate))
  }

  test("rejects an expired Redis cached rate") {
    val rate = createRate(now.minusMinutes(5).minusNanos(1L))
    val result = for {
      cache <- FakeStringCache.create(Map(key -> StoredValue(payload(rate), maxAge)))
      rates = redisRates(cache, provider(Right(List.empty)))
      found <- rates.get(pair)
    } yield found

    assert(result.unsafeRunSync().left.toOption.exists(_.isInstanceOf[Error.RateUnavailable]))
  }

  test("rejects invalid Redis payloads") {
    val result = for {
      cache <- FakeStringCache.create(Map(key -> StoredValue("not-json", maxAge)))
      rates = redisRates(cache, provider(Right(List.empty)))
      found <- rates.get(pair)
    } yield found

    assert(result.unsafeRunSync().left.toOption.exists(_.isInstanceOf[Error.RateUnavailable]))
  }

  test("refresh writes provider rates to Redis with the configured TTL") {
    val rate = createRate(now)
    val result = for {
      cache <- FakeStringCache.create(Map.empty)
      rates = redisRates(cache, provider(Right(List(rate))))
      refreshed <- rates.refresh
      stored    <- cache.snapshot
    } yield (refreshed, stored)

    val (refreshed, stored) = result.unsafeRunSync()

    assert(refreshed == Right(()))
    assert(stored.get(lockKey).map(_.expiresIn).contains(lockTtl))
    assert(stored.get(key).map(_.expiresIn).contains(maxAge))
    assert(stored.get(key).map(_.value).exists(_.contains("USD")))
    assert(stored.get(key).map(_.value).exists(_.contains("JPY")))
  }

  test("refresh failure preserves existing Redis values") {
    val oldRate = createRate(now.minusMinutes(1))
    val failure = Error.OneFrameLookupFailed("provider unavailable")
    val result = for {
      cache <- FakeStringCache.create(Map(key -> StoredValue(payload(oldRate), maxAge)))
      rates = redisRates(cache, provider(Left(failure)))
      refreshed <- rates.refresh
      stored    <- cache.snapshot
    } yield (refreshed, stored)

    assert(result.unsafeRunSync() == (
      Left(failure),
      Map(
        key     -> StoredValue(payload(oldRate), maxAge),
        lockKey -> StoredValue("locked", lockTtl)
      )
    ))
  }

  test("refresh skips provider lookup when another replica holds the refresh lock") {
    val result = for {
      cache <- FakeStringCache.create(Map(lockKey -> StoredValue("locked", lockTtl)))
      calls <- Ref.of[IO, Int](0)
      rates = redisRates(
        cache,
        new OneFrame[IO] {
          override def get(_pairs: List[Rate.Pair]): IO[Either[Error, List[Rate]]] =
            calls.update(_ + 1).as(Right(List(createRate(now))))
        }
      )
      refreshed <- rates.refresh
      stored    <- cache.snapshot
      count     <- calls.get
    } yield (refreshed, stored, count)

    val (refreshed, stored, count) = result.unsafeRunSync()

    assert(refreshed == Right(()))
    assert(stored == Map(lockKey -> StoredValue("locked", lockTtl)))
    assert(count == 0)
  }

  private def redisRates(cache: FakeStringCache, oneFrame: OneFrame[IO]): RedisRatesCache[IO] =
    new RedisRatesCache[IO](oneFrame, cache, keyPrefix, maxAge, lockTtl, IO.pure(now))

  private def provider(result: Either[Error, List[Rate]]): OneFrame[IO] =
    new OneFrame[IO] {
      override def get(_pairs: List[Rate.Pair]): IO[Either[Error, List[Rate]]] =
        IO.pure(result)
    }

  private def createRate(timestamp: OffsetDateTime): Rate =
    Rate(pair, Price(BigDecimal("0.71")), Timestamp(timestamp))

  private def payload(rate: Rate): String =
    s"""{"from":"${Currency.show.show(rate.pair.from)}","to":"${Currency.show.show(rate.pair.to)}","price":${rate.price.value},"timestamp":"${rate.timestamp.value}"}"""
}

final case class StoredValue(value: String, expiresIn: FiniteDuration)

final class FakeStringCache private (
    values: Ref[IO, Map[String, StoredValue]]
) extends StringCache[IO] {

  override def get(key: String): IO[Option[String]] =
    values.get.map(_.get(key).map(_.value))

  override def setEx(key: String, value: String, expiresIn: FiniteDuration): IO[Unit] =
    values.update(_.updated(key, StoredValue(value, expiresIn)))

  override def trySetEx(key: String, value: String, expiresIn: FiniteDuration): IO[Boolean] =
    values.modify { current =>
      if (current.contains(key)) (current, false)
      else (current.updated(key, StoredValue(value, expiresIn)), true)
    }

  def snapshot: IO[Map[String, StoredValue]] =
    values.get
}

object FakeStringCache {
  def create(initial: Map[String, StoredValue]): IO[FakeStringCache] =
    Ref.of[IO, Map[String, StoredValue]](initial).map(new FakeStringCache(_))
}
