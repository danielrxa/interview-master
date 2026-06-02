package forex.services.rates

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import cats.effect.{ IO, Timer }
import cats.effect.concurrent.Ref
import forex.services.rates.errors.Error
import org.scalatest.funsuite.AnyFunSuite

final class RatesRefresherSpec extends AnyFunSuite {

  private implicit val timer: Timer[IO] = IO.timer(ExecutionContext.global)

  test("refreshes immediately when the stream starts") {
    val result = for {
      calls <- Ref.of[IO, Int](0)
      refresh = calls.update(_ + 1).as(Right(()): Either[Error, Unit])
      _     <- new RatesRefresher[IO](refresh, 1.hour).stream.take(1).compile.drain
      count <- calls.get
    } yield count

    assert(result.unsafeRunSync() == 1)
  }

  test("refreshes periodically after the initial refresh") {
    val result = for {
      calls <- Ref.of[IO, Int](0)
      refresh = calls.update(_ + 1).as(Right(()): Either[Error, Unit])
      _     <- new RatesRefresher[IO](refresh, 10.millis).stream.take(3).compile.drain
      count <- calls.get
    } yield count

    assert(result.unsafeRunSync() == 3)
  }

  test("continues refreshing after a provider failure") {
    val result = for {
      calls <- Ref.of[IO, Int](0)
      refresh = calls.update(_ + 1).as(
        Left(Error.OneFrameLookupFailed("provider unavailable")): Either[Error, Unit]
      )
      _     <- new RatesRefresher[IO](refresh, 10.millis).stream.take(3).compile.drain
      count <- calls.get
    } yield count

    assert(result.unsafeRunSync() == 3)
  }

  test("does not run overlapping refreshes") {
    val result = for {
      active    <- Ref.of[IO, Int](0)
      maxActive <- Ref.of[IO, Int](0)
      refresh =
        active.update(_ + 1) *>
          active.get.flatMap(current => maxActive.update(previous => previous.max(current))) *>
          timer.sleep(20.millis) *>
          active.update(_ - 1) *>
          IO.pure(Right(()): Either[Error, Unit])
      _       <- new RatesRefresher[IO](refresh, 1.millis).stream.take(3).compile.drain
      maximum <- maxActive.get
    } yield maximum

    assert(result.unsafeRunSync() == 1)
  }
}
