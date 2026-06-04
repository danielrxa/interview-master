package forex

import cats.effect.{ Concurrent, Timer }
import forex.config.ApplicationConfig
import forex.http.rates.RatesHttpRoutes
import forex.observability.{ SpanAttribute, Tracing }
import forex.services._
import forex.programs._
import io.opentelemetry.api.trace.SpanKind
import org.http4s._
import org.http4s.implicits._
import org.http4s.server.middleware.Logger
import org.http4s.server.middleware.{ AutoSlash, Timeout }

class Module[F[_]: Concurrent: Timer](
    config: ApplicationConfig,
    ratesService: RatesService[F],
    tracing: Tracing[F] = Tracing.noop[F]
) {

  private val ratesProgram: RatesProgram[F] = RatesProgram[F](ratesService)

  private val ratesHttpRoutes: HttpRoutes[F] = new RatesHttpRoutes[F](ratesProgram).routes

  type PartialMiddleware = HttpRoutes[F] => HttpRoutes[F]
  type TotalMiddleware   = HttpApp[F] => HttpApp[F]

  private val routesMiddleware: PartialMiddleware = {
    { http: HttpRoutes[F] =>
      AutoSlash(http)
    }
  }

  private val appMiddleware: TotalMiddleware = { http: HttpApp[F] =>
    Timeout(config.http.timeout)(traceHttp(Logger.httpApp(logHeaders = false, logBody = false)(http)))
  }

  private val http: HttpRoutes[F] = ratesHttpRoutes

  val httpApp: HttpApp[F] = appMiddleware(routesMiddleware(http).orNotFound)

  private def traceHttp(http: HttpApp[F]): HttpApp[F] =
    HttpApp[F] { request =>
      tracing.spanWithResult(
        name = s"http ${request.method.name} ${request.uri.path.renderString}",
        kind = SpanKind.SERVER,
        attributes = List(
          SpanAttribute("http.method", request.method.name),
          SpanAttribute("http.route", request.uri.path.renderString)
        )
      )(http.run(request))(
        response =>
          List(
            SpanAttribute("http.status_code", response.status.code.toString)
          ),
        response =>
          if (response.status.code >= 500) Some(response.status.reason)
          else None
      )
    }
}
