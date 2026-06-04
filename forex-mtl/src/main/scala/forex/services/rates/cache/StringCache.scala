package forex.services.rates.cache

import scala.concurrent.duration.FiniteDuration

trait StringCache[F[_]] {
  def get(key: String): F[Option[String]]
  def setEx(key: String, value: String, expiresIn: FiniteDuration): F[Unit]
  def trySetEx(key: String, value: String, expiresIn: FiniteDuration): F[Boolean]
}
