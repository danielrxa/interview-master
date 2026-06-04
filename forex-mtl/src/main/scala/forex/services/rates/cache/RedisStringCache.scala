package forex.services.rates.cache

import scala.concurrent.duration.FiniteDuration
import cats.Functor
import cats.syntax.functor._
import dev.profunktor.redis4cats.algebra.RedisCommands
import dev.profunktor.redis4cats.effects.{ SetArg, SetArgs }

final class RedisStringCache[F[_]: Functor](
    redis: RedisCommands[F, String, String]
) extends StringCache[F] {

  override def get(key: String): F[Option[String]] =
    redis.get(key)

  override def setEx(key: String, value: String, expiresIn: FiniteDuration): F[Unit] =
    redis.setEx(key, value, expiresIn).void

  override def trySetEx(key: String, value: String, expiresIn: FiniteDuration): F[Boolean] =
    redis.set(
      key,
      value,
      SetArgs(
        existence = Some(SetArg.Existence.Nx),
        ttl = Some(SetArg.Ttl.Ex(expiresIn))
      )
    )
}
