package forex.config

import cats.effect.IO
import org.scalatest.funsuite.AnyFunSuite

final class ConfigSpec extends AnyFunSuite {

  test("loads HTTP and One-Frame configuration") {
    val config = Config.stream[IO]("app").compile.lastOrError.unsafeRunSync()

    assert(config.http.host == "0.0.0.0")
    assert(config.http.port == 8081)
    assert(config.oneFrame.baseUri == "http://localhost:8080")
    assert(config.oneFrame.token == "10dc303535874aeccc86a8251e6992f5")
    assert(config.oneFrame.refreshInterval.toMinutes == 4L)
    assert(config.oneFrame.maxRateAge.toMinutes == 5L)
    assert(config.redis.uri == "redis://localhost:6379")
    assert(config.redis.keyPrefix == "forex:rates")
    assert(config.redis.refreshLockTtl.toSeconds == 30L)
  }
}
