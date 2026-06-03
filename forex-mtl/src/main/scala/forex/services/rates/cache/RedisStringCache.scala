package forex.services.rates.cache

import scala.concurrent.duration.FiniteDuration
import cats.Functor
import cats.syntax.functor._
import dev.profunktor.redis4cats.algebra.RedisCommands

final class RedisStringCache[F[_]: Functor](
    redis: RedisCommands[F, String, String]
) extends StringCache[F] {

  override def get(key: String): F[Option[String]] =
    redis.get(key)

  override def setEx(key: String, value: String, expiresIn: FiniteDuration): F[Unit] =
    redis.setEx(key, value, expiresIn).void
}
