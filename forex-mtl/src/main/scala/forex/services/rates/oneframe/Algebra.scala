package forex.services.rates.oneframe

import forex.domain.Rate
import forex.services.rates.errors.Error

trait Algebra[F[_]] {
  def get(pairs: List[Rate.Pair]): F[Either[Error, List[Rate]]]
}
