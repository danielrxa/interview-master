# Forex Proxy

Local HTTP proxy for currency exchange rates backed by the One-Frame service.

## Design

The proxy keeps rates in an in-memory cache instead of calling One-Frame for
every incoming request.

At startup, and then every four minutes, it requests all supported directed
currency pairs in one One-Frame call:

```text
GET http://localhost:8080/rates?pair=AUDCAD&pair=AUDCHF&...
token: 10dc303535874aeccc86a8251e6992f5
```

The cache uses `Ref[F, Map[Rate.Pair, Rate]]`, so updates are atomic and reads
are safe while a refresh is running. Failed refresh attempts preserve the
previous cache contents. Rates older than five minutes are never returned.

There are nine supported currencies and 72 directed pairs. A refresh every four
minutes results in approximately 360 One-Frame calls per day, below the
provider's limit of 1,000 calls per token per day.

## Requirements

- JDK 17
- sbt
- Docker

## Run Locally

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
- cache freshness, expiration, missing rates, and atomic replacement;
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
Statement coverage: 75.84%
Branch coverage:    64.86%
Statements:         248 / 327
Tests:              42 passing
```

Coverage graph:

```text
Statement  [███████████████░░░░░] 75.84%
Branch     [█████████████░░░░░░░] 64.86%
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
- The cache is local to one process and is cleared when the process restarts.
- Each application replica has its own cache and consumes its own refresh calls.
  A shared cache or leader election should be considered before running
  multiple replicas.
- Refresh failures currently preserve cached data but are not logged. Production
  deployment should add structured logging and metrics.
- The HTTP server starts concurrently with the initial refresh. Requests made
  before that refresh completes receive `RATE_UNAVAILABLE`.
