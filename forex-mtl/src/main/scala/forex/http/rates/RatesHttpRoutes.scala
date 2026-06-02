package forex.http
package rates

import cats.effect.Sync
import cats.syntax.apply._
import cats.syntax.flatMap._
import forex.programs.RatesProgram
import forex.programs.rates.{ Protocol => RatesProgramProtocol }
import forex.programs.rates.errors.Error
import org.http4s.{ HttpRoutes, Query }
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router

class RatesHttpRoutes[F[_]: Sync](rates: RatesProgram[F]) extends Http4sDsl[F] {

  import Converters._, QueryParams._, Protocol._

  private[http] val prefixPath = "/rates"

  private val httpRoutes: HttpRoutes[F] = HttpRoutes.of[F] {
    case request @ GET -> Root =>
      val query = request.uri.query
      val parsed = (
        parseCurrency(query, "from"),
        parseCurrency(query, "to")
      ).mapN(RatesProgramProtocol.GetRatesRequest.apply)

      parsed.fold(
        message => BadRequest(ErrorApiResponse("INVALID_REQUEST", message)),
        rates.get(_).flatMap {
        case Right(rate)                         => Ok(rate.asGetApiResponse)
        case Left(Error.UnsupportedPair(message)) => BadRequest(ErrorApiResponse("UNSUPPORTED_PAIR", message))
        case Left(Error.RateUnavailable(_)) =>
          ServiceUnavailable(ErrorApiResponse("RATE_UNAVAILABLE", "No fresh rate is currently available"))
        case Left(Error.RateLookupFailed(_)) =>
          BadGateway(ErrorApiResponse("RATE_PROVIDER_UNAVAILABLE", "The rates provider is currently unavailable"))
        }
      )
  }

  private def parseCurrency(query: Query, parameter: String): Either[String, forex.domain.Currency] =
    query.multiParams.get(parameter).toList.flatten match {
      case Nil         => Left(s"Missing query parameter: $parameter")
      case value :: Nil => decode(value)
      case _           => Left(s"Query parameter must be provided once: $parameter")
    }

  val routes: HttpRoutes[F] = Router(
    prefixPath -> httpRoutes
  )

}
