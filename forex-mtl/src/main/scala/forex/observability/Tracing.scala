package forex.observability

import cats.effect.Sync
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.{ Span, SpanKind, StatusCode, Tracer }

final case class SpanAttribute(key: String, value: String)

trait Tracing[F[_]] {
  def span[A](
      name: String,
      kind: SpanKind,
      attributes: List[SpanAttribute] = List.empty
  )(fa: F[A]): F[A]

  def spanWithResult[A](
      name: String,
      kind: SpanKind,
      attributes: List[SpanAttribute] = List.empty
  )(
      fa: F[A]
  )(
      resultAttributes: A => List[SpanAttribute],
      resultError: A => Option[String]
  ): F[A]
}

object Tracing {

  def noop[F[_]]: Tracing[F] =
    new Tracing[F] {
      override def span[A](
          name: String,
          kind: SpanKind,
          attributes: List[SpanAttribute]
      )(fa: F[A]): F[A] =
        {
          val _ = (name, kind, attributes)
          fa
        }

      override def spanWithResult[A](
          name: String,
          kind: SpanKind,
          attributes: List[SpanAttribute]
      )(
          fa: F[A]
      )(
          resultAttributes: A => List[SpanAttribute],
          resultError: A => Option[String]
      ): F[A] =
        {
          val _ = (name, kind, attributes, resultAttributes, resultError)
          fa
        }
    }

  def fromOpenTelemetry[F[_]: Sync](openTelemetry: OpenTelemetry, serviceName: String): Tracing[F] =
    new LiveTracing[F](openTelemetry.getTracer(serviceName))
}

final class LiveTracing[F[_]: Sync](tracer: Tracer) extends Tracing[F] {

  override def span[A](
      name: String,
      kind: SpanKind,
      attributes: List[SpanAttribute]
  )(fa: F[A]): F[A] =
    spanWithResult(name, kind, attributes)(fa)(_ => List.empty, _ => None)

  override def spanWithResult[A](
      name: String,
      kind: SpanKind,
      attributes: List[SpanAttribute]
  )(
      fa: F[A]
  )(
      resultAttributes: A => List[SpanAttribute],
      resultError: A => Option[String]
  ): F[A] =
    Sync[F].bracket(startSpan(name, kind, attributes)) { active =>
      fa.flatTap(result => finishSuccess(active.span, resultAttributes(result), resultError(result)))
          .onError {
            case error => recordException(active.span, error)
          }
    }(active => Sync[F].delay(active.close()))

  private def startSpan(
      name: String,
      kind: SpanKind,
      attributes: List[SpanAttribute]
  ): F[ActiveSpan] =
    Sync[F].delay {
      val span = tracer.spanBuilder(name).setSpanKind(kind).startSpan()
      attributes.foreach { attribute =>
        val _ = span.setAttribute(attribute.key, attribute.value)
      }
      new ActiveSpan(span, span.makeCurrent())
    }

  private def finishSuccess(span: Span, attributes: List[SpanAttribute], error: Option[String]): F[Unit] =
    Sync[F].delay {
      attributes.foreach { attribute =>
        val _ = span.setAttribute(attribute.key, attribute.value)
      }
      error.foreach { message =>
        val _ = span.setStatus(StatusCode.ERROR, message)
        val _ = span.setAttribute(AttributeKey.stringKey("error.message"), message)
      }
    }

  private def recordException(span: Span, error: Throwable): F[Unit] =
    Sync[F].delay {
      span.recordException(error)
      val _ = span.setStatus(StatusCode.ERROR, error.getMessage)
    }

  private final class ActiveSpan(val span: Span, scope: AutoCloseable) {
    def close(): Unit = {
      scope.close()
      span.end()
    }
  }
}
