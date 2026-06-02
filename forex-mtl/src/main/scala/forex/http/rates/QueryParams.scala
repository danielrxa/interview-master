package forex.http.rates

import forex.domain.Currency
import org.http4s.{ ParseFailure, QueryParamDecoder }
import org.http4s.dsl.impl.QueryParamDecoderMatcher

object QueryParams {

  private[rates] def decode(value: String): Either[String, Currency] =
    Currency.fromString(value)

  private[http] implicit val currencyQueryParam: QueryParamDecoder[Currency] =
    QueryParamDecoder[String].emap(value =>
      decode(value).left.map(message => ParseFailure(message, message))
    )

  object FromQueryParam extends QueryParamDecoderMatcher[Currency]("from")
  object ToQueryParam extends QueryParamDecoderMatcher[Currency]("to")

}
