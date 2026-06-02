package forex.services.rates

import scala.concurrent.duration.FiniteDuration
import cats.effect.{ Sync, Timer }
import cats.syntax.functor._
import forex.services.rates.errors.Error
import fs2.Stream

final class RatesRefresher[F[_]: Sync: Timer](
    refresh: F[Either[Error, Unit]],
    refreshInterval: FiniteDuration
) {

  val stream: Stream[F, Unit] =
    (Stream.eval(refresh) ++
      Stream.awakeEvery[F](refreshInterval).evalMap(_ => refresh)).void
}
