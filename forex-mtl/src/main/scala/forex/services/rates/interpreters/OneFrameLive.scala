package forex.services.rates.interpreters

import cats.effect.Sync
import cats.syntax.applicativeError._
import cats.syntax.functor._
import forex.domain.Rate
import forex.observability.{ SpanAttribute, Tracing }
import forex.services.rates.errors.Error
import forex.services.rates.oneframe.{ Algebra, Protocol }
import io.opentelemetry.api.trace.SpanKind
import org.http4s.{ EntityDecoder, Header, Method, Query, Request, Uri }
import org.http4s.client.Client
import org.http4s.circe.jsonOf
import org.typelevel.ci.CIString

final class OneFrameLive[F[_]: Sync](
    client: Client[F],
    baseUri: Uri,
    token: String,
    tracing: Tracing[F] = Tracing.noop[F]
) extends Algebra[F] {

  import Protocol._

  private implicit val responseDecoder: EntityDecoder[F, List[OneFrameResponse]] =
    jsonOf[F, List[OneFrameResponse]]

  override def get(pairs: List[Rate.Pair]): F[Either[Error, List[Rate]]] =
    if (pairs.isEmpty)
      Sync[F].pure(Left(Error.OneFrameLookupFailed("At least one currency pair is required")))
    else {
      val query = Query.fromPairs(pairs.map(pair => "pair" -> render(pair)): _*)
      val uri   = (baseUri / "rates").copy(query = query)
      val request = Request[F](Method.GET, uri).putHeaders(
        Header.Raw(CIString("token"), token)
      )
      val lookup: F[Either[Error, List[Rate]]] =
        client
          .expect[List[OneFrameResponse]](request)
          .attempt
          .map(_.left.map[Error](error => Error.OneFrameLookupFailed(error.getMessage)).map(_.map(_.asRate)))

      tracing.spanWithResult(
        name = "oneframe.rates.get",
        kind = SpanKind.CLIENT,
        attributes = List(
          SpanAttribute("peer.service", "one-frame"),
          SpanAttribute("http.method", Method.GET.name),
          SpanAttribute("http.url", uri.renderString),
          SpanAttribute("forex.pair.count", pairs.size.toString)
        )
      )(lookup)(
        result =>
          List(
            SpanAttribute("oneframe.result", result.fold(_ => "failure", _ => "success")),
            SpanAttribute("oneframe.rate.count", result.fold(_ => 0, _.size).toString)
          ),
        _.left.toOption.map(describe)
      )
    }

  private def render(pair: Rate.Pair): String =
    s"${CurrencyCode(pair.from)}${CurrencyCode(pair.to)}"

  private def describe(error: Error): String =
    error match {
      case Error.OneFrameLookupFailed(message) => message
      case Error.RateUnavailable(message)      => message
      case Error.UnsupportedPair(message)      => message
    }

  private object CurrencyCode {
    def apply(currency: forex.domain.Currency): String =
      forex.domain.Currency.show.show(currency)
  }
}
