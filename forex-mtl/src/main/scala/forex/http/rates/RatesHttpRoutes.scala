package forex.http
package rates

import cats.effect.Sync
import cats.syntax.apply._
import cats.syntax.flatMap._
import forex.programs.RatesProgram
import forex.programs.rates.{ Protocol => RatesProgramProtocol }
import forex.programs.rates.errors.Error
import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router

class RatesHttpRoutes[F[_]: Sync](rates: RatesProgram[F]) extends Http4sDsl[F] {

  import Converters._, QueryParams._, Protocol._

  private[http] val prefixPath = "/rates"

  private val httpRoutes: HttpRoutes[F] = HttpRoutes.of[F] {
    case request @ GET -> Root =>
      val query = request.uri.query.params
      val parsed = (
        query.get("from").toRight("Missing query parameter: from").flatMap(decode),
        query.get("to").toRight("Missing query parameter: to").flatMap(decode)
      ).mapN(RatesProgramProtocol.GetRatesRequest.apply)

      parsed.fold(
        message => BadRequest(ErrorApiResponse(message)),
        rates.get(_).flatMap {
        case Right(rate)                          => Ok(rate.asGetApiResponse)
        case Left(Error.UnsupportedPair(message)) => BadRequest(ErrorApiResponse(message))
        case Left(Error.RateUnavailable(message))  => ServiceUnavailable(ErrorApiResponse(message))
        case Left(Error.RateLookupFailed(message)) => BadGateway(ErrorApiResponse(message))
        }
      )
  }

  val routes: HttpRoutes[F] = Router(
    prefixPath -> httpRoutes
  )

}
