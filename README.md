# Forex Proxy

Local HTTP proxy for currency exchange rates backed by the One-Frame service
and Redis.

## Design

The proxy keeps rates in Redis instead of calling One-Frame for every incoming
request.

At startup, and then every four minutes, it requests all supported directed
currency pairs in one One-Frame call:

```text
GET http://localhost:8080/rates?pair=AUDCAD&pair=AUDCHF&...
token: 10dc303535874aeccc86a8251e6992f5
```

Each Redis key uses the configured `max-rate-age` as TTL. Failed refresh
attempts preserve the previous cache contents. Rates older than five minutes
are never returned, even if a cached value is still present.

There are nine supported currencies and 72 directed pairs. A refresh every four
minutes results in approximately 360 One-Frame calls per day, below the
provider's limit of 1,000 calls per token per day.

## Requirements

- JDK 17
- sbt
- Docker
- Redis on `localhost:6379`

## Run Locally

Start Redis on port `6379`:

```bash
docker run --rm -p 6379:6379 redis:7-alpine
```

Start One-Frame on port `8080`:

```bash
docker pull paidyinc/one-frame
docker run --rm -p 8080:8080 paidyinc/one-frame
```

In another terminal, start the proxy on port `8081`:

```bash
cd forex-mtl
sbt run
```

Request a cached rate:

```bash
curl 'http://localhost:8081/rates?from=USD&to=JPY'
```

The proxy stores rates in Redis with keys like:

```text
forex:rates:USDJPY
```

Example response:

```json
{
  "from": "USD",
  "to": "JPY",
  "price": 0.8605628566092338,
  "timestamp": "2026-06-02T02:09:29.799Z"
}
```

## API Errors

Errors have a stable `code` and a public `message`.

| Status | Code | Situation |
| --- | --- | --- |
| `400` | `INVALID_REQUEST` | Missing, invalid, or repeated query parameter |
| `400` | `UNSUPPORTED_PAIR` | A pair contains the same currency twice |
| `503` | `RATE_UNAVAILABLE` | No fresh cached rate is available |
| `502` | `RATE_PROVIDER_UNAVAILABLE` | The external rates provider is unavailable |

Example:

```bash
curl 'http://localhost:8081/rates?from=XXX&to=JPY'
```

```json
{
  "code": "INVALID_REQUEST",
  "message": "Unsupported currency: XXX"
}
```

## Observability

The application includes optional OpenTelemetry tracing. It is disabled by
default, so local development works without an OpenTelemetry collector.

Enable tracing by setting:

```bash
OTEL_ENABLED=true \
OTEL_SERVICE_NAME=forex-mtl \
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317 \
sbt run
```

When enabled, spans are exported through OTLP/gRPC. The application instruments:

- incoming HTTP requests;
- One-Frame HTTP calls;
- Redis cache lookups;
- periodic Redis cache refreshes.

Relevant span names:

```text
http GET /rates
oneframe.rates.get
rates.cache.get
rates.cache.refresh
```

Useful attributes include:

```text
http.method
http.route
http.status_code
forex.from
forex.to
forex.pair.count
cache.result
refresh.result
oneframe.result
```

## Tests

The tests do not require Docker or network access. The One-Frame HTTP client is
tested with an in-memory http4s application.

```bash
sbt test
```

The test suite covers:

- supported currencies and directed pair generation;
- One-Frame URL construction, token header, decoding, and failures;
- One-Frame timestamp formats with and without an explicit UTC offset;
- Redis-backed cache freshness, expiration, missing rates, TTL writes, and
  invalid payload handling;
- in-memory cache behavior kept as an alternative implementation test;
- initial and periodic refresh execution without overlapping updates;
- refresh failure handling;
- program error mapping;
- HTTP success responses and descriptive API errors;
- application configuration and route wiring.

## Coverage

Code coverage is measured with `sbt-scoverage`.

Generate the report:

```bash
cd forex-mtl
sbt clean coverage test coverageReport
```

Latest local report:

```text
Statement coverage: 75.97%
Branch coverage:    65.85%
Statements:         332 / 437
Tests:              47 passing
```

Coverage graph:

```text
Statement  [███████████████░░░░░] 75.97%
Branch     [█████████████░░░░░░░] 65.85%
```

Generated report files:

```text
forex-mtl/target/scala-2.13/scoverage-report/index.html
forex-mtl/target/scala-2.13/scoverage-report/scoverage.xml
forex-mtl/target/scala-2.13/coverage-report/cobertura.xml
```

The score includes application wiring in `Main.scala`, which is intentionally
not covered by unit tests because it starts real resources and the HTTP server.
Most core behavior is covered through unit tests around the One-Frame client,
decoders, cache, periodic refresh process, program layer, and HTTP routes.

## Assumptions And Limitations

- One-Frame timestamps without an explicit offset are interpreted as UTC.
- Redis keeps cached rates when the Scala process restarts, as long as the Redis
  container is still running and the keys have not expired.
- Redis keys expire after the configured `max-rate-age`, so stale rates are not
  served after process restarts.
- The example Redis command uses `--rm` and no volume, so Redis data is lost
  when the Redis container itself stops. Use Redis persistence or a Docker
  volume if container restarts should keep data.
- Multiple Scala replicas can share the same Redis cache, but each replica still
  runs its own refresh process. Use leader election or a dedicated refresh
  worker before deploying multiple replicas.
- Refresh failures preserve cached data and are logged. OpenTelemetry traces can
  also report refresh failures when tracing is enabled.
- The HTTP server starts concurrently with the initial refresh. Requests made
  before that refresh completes receive `RATE_UNAVAILABLE`.
