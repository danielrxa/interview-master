package forex.services.rates.oneframe

import java.time.{ LocalDateTime, OffsetDateTime, ZoneOffset }
import scala.util.Try
import forex.domain.{ Currency, Price, Rate, Timestamp }
import io.circe.Decoder

object Protocol {

  final case class OneFrameResponse(
      from: Currency,
      to: Currency,
      price: Price,
      timestamp: Timestamp
  ) {
    def asRate: Rate = Rate(Rate.Pair(from, to), price, timestamp)
  }

  implicit val currencyDecoder: Decoder[Currency] =
    Decoder.decodeString.emap(Currency.fromString)

  implicit val priceDecoder: Decoder[Price] =
    Decoder.decodeBigDecimal.map(Price(_))

  implicit val timestampDecoder: Decoder[Timestamp] =
    Decoder.decodeString.emap { value =>
      Try(OffsetDateTime.parse(value))
        .orElse(Try(LocalDateTime.parse(value).atOffset(ZoneOffset.UTC)))
        .toEither
        .left
        .map(_.getMessage)
        .map(Timestamp(_))
    }

  implicit val oneFrameResponseDecoder: Decoder[OneFrameResponse] =
    Decoder.forProduct4("from", "to", "price", "time_stamp")(OneFrameResponse.apply)
}
