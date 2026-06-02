package forex.services.rates.interpreters

import cats.effect.IO
import cats.effect.concurrent.Ref
import forex.domain.{ Currency, Rate }
import forex.services.rates.errors.Error
import org.http4s.{ HttpApp, Request, Response, Status, Uri }
import org.http4s.client.Client
import org.scalatest.funsuite.AnyFunSuite

final class OneFrameLiveSpec extends AnyFunSuite {

  private val pair = Rate.Pair(Currency.USD, Currency.JPY)

  test("requests multiple pairs with the token and maps the response") {
    val requestedPairs = List(pair, Rate.Pair(Currency.EUR, Currency.CHF))
    val result = for {
      captured <- Ref.of[IO, Option[Request[IO]]](None)
      app = HttpApp[IO](request =>
        captured
          .set(Some(request))
          .as(Response[IO](Status.Ok).withEntity(
            """[{"from":"USD","to":"JPY","price":0.71,"time_stamp":"2019-01-01T00:00:00.000"}]"""
          ))
      )
      live = new OneFrameLive[IO](Client.fromHttpApp(app), Uri.unsafeFromString("http://one-frame"), "secret")
      rates   <- live.get(requestedPairs)
      request <- captured.get.map(_.get)
    } yield (rates, request)

    val (rates, request) = result.unsafeRunSync()

    assert(request.uri.path.renderString == "/rates")
    assert(request.uri.query.multiParams("pair") == Seq("USDJPY", "EURCHF"))
    assert(request.headers.headers.exists(header => header.name.toString == "token" && header.value == "secret"))
    assert(rates.toOption.get.map(_.pair) == List(pair))
  }

  test("maps unsuccessful responses to a lookup failure") {
    val app  = HttpApp[IO](_ => IO.pure(Response[IO](Status.InternalServerError)))
    val live = new OneFrameLive[IO](Client.fromHttpApp(app), Uri.unsafeFromString("http://one-frame"), "secret")

    assert(live.get(List(pair)).unsafeRunSync().left.toOption.exists(_.isInstanceOf[Error.OneFrameLookupFailed]))
  }

  test("maps invalid JSON to a lookup failure") {
    val app  = HttpApp[IO](_ => IO.pure(Response[IO](Status.Ok).withEntity("invalid-json")))
    val live = new OneFrameLive[IO](Client.fromHttpApp(app), Uri.unsafeFromString("http://one-frame"), "secret")

    assert(live.get(List(pair)).unsafeRunSync().left.toOption.exists(_.isInstanceOf[Error.OneFrameLookupFailed]))
  }

  test("maps transport failures to a lookup failure") {
    val app  = HttpApp[IO](_ => IO.raiseError(new RuntimeException("connection refused")))
    val live = new OneFrameLive[IO](Client.fromHttpApp(app), Uri.unsafeFromString("http://one-frame"), "secret")

    assert(live.get(List(pair)).unsafeRunSync() == Left(Error.OneFrameLookupFailed("connection refused")))
  }

  test("rejects an empty pair list without calling One-Frame") {
    val result = for {
      called <- Ref.of[IO, Boolean](false)
      app = HttpApp[IO](_ => called.set(true).as(Response[IO](Status.Ok)))
      live = new OneFrameLive[IO](Client.fromHttpApp(app), Uri.unsafeFromString("http://one-frame"), "secret")
      rates     <- live.get(List.empty)
      wasCalled <- called.get
    } yield (rates, wasCalled)

    assert(result.unsafeRunSync() == (
      Left(Error.OneFrameLookupFailed("At least one currency pair is required")),
      false
    ))
  }
}
