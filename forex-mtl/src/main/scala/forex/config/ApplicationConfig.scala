package forex.config

import scala.concurrent.duration.FiniteDuration

case class ApplicationConfig(
    http: HttpConfig,
    oneFrame: OneFrameConfig,
    redis: RedisConfig,
    observability: ObservabilityConfig
)

case class HttpConfig(
    host: String,
    port: Int,
    timeout: FiniteDuration
)

case class OneFrameConfig(
    baseUri: String,
    token: String,
    refreshInterval: FiniteDuration,
    maxRateAge: FiniteDuration
)

case class RedisConfig(
    uri: String,
    keyPrefix: String
)

case class ObservabilityConfig(
    enabled: Boolean,
    serviceName: String,
    otlpEndpoint: String
)
