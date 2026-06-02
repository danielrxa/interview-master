package forex.services.rates.interpreters

import cats.effect.Sync
import cats.syntax.applicativeError._
import cats.syntax.functor._
import forex.domain.Rate
import forex.services.rates.errors.Error
import forex.services.rates.oneframe.{ Algebra, Protocol }
import org.http4s.{ EntityDecoder, Header, Method, Query, Request, Uri }
import org.http4s.client.Client
import org.http4s.circe.jsonOf
import org.typelevel.ci.CIString

final class OneFrameLive[F[_]: Sync](
    client: Client[F],
    baseUri: Uri,
    token: String
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

      client
        .expect[List[OneFrameResponse]](request)
        .attempt
        .map(_.left.map(error => Error.OneFrameLookupFailed(error.getMessage)).map(_.map(_.asRate)))
    }

  private def render(pair: Rate.Pair): String =
    s"${CurrencyCode(pair.from)}${CurrencyCode(pair.to)}"

  private object CurrencyCode {
    def apply(currency: forex.domain.Currency): String =
      forex.domain.Currency.show.show(currency)
  }
}
