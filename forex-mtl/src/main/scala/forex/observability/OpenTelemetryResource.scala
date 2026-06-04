package forex.observability

import cats.effect.{ Resource => CatsResource, Sync }
import cats.syntax.functor._
import forex.config.ObservabilityConfig
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.{ AttributeKey, Attributes }
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.resources.{ Resource => OtelResource }
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor

object OpenTelemetryResource {

  def create[F[_]: Sync](config: ObservabilityConfig): CatsResource[F, OpenTelemetry] =
    if (config.enabled) enabled(config)
    else CatsResource.pure(OpenTelemetry.noop())

  private def enabled[F[_]: Sync](config: ObservabilityConfig): CatsResource[F, OpenTelemetry] =
    CatsResource.make(Sync[F].delay(build(config)))(sdk =>
      Sync[F].delay {
        sdk.getSdkTracerProvider.shutdown()
        ()
      }
    ).widen[OpenTelemetry]

  private def build(config: ObservabilityConfig): OpenTelemetrySdk = {
    val exporter = OtlpGrpcSpanExporter
      .builder()
      .setEndpoint(config.otlpEndpoint)
      .build()

    val spanProcessor = BatchSpanProcessor
      .builder(exporter)
      .build()

    val resource = OtelResource.create(
      Attributes.of(AttributeKey.stringKey("service.name"), config.serviceName)
    )

    val tracerProvider = SdkTracerProvider
      .builder()
      .setResource(resource)
      .addSpanProcessor(spanProcessor)
      .build()

    OpenTelemetrySdk
      .builder()
      .setTracerProvider(tracerProvider)
      .build()
  }
}
