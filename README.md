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

When the proxy runs with multiple replicas, every replica can receive HTTP
requests, but only one replica should refresh rates during each refresh cycle.
The refresh process is coordinated through a Redis lock:

```text
forex:rates:refresh-lock
```

The lock is acquired with `SET NX EX`, so only one replica calls One-Frame while
the lock exists. Other replicas skip that refresh cycle and continue serving
rates from Redis.

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

## Run With Docker Compose

The repository includes a Compose stack that starts all required services:

- Redis on `localhost:6379`;
- One-Frame on `localhost:8080`;
- Forex proxy on `localhost:8081`.

Build and start the full stack:

```bash
docker compose up --build
```

Call the proxy:

```bash
curl 'http://localhost:8081/rates?from=USD&to=JPY'
```

Stop the stack:

```bash
docker compose down
```

Remove the Redis volume too:

```bash
docker compose down -v
```

The application image is built from `forex-mtl/Dockerfile`. The Docker build
uses `sbt stage` to create a production-style executable distribution and then
copies only the staged application into a JRE runtime image.

Inside Docker Compose the service discovery values are injected through
environment variables:

```text
ONE_FRAME_BASE_URI=http://one-frame:8080
REDIS_URI=redis://redis:6379
REDIS_REFRESH_LOCK_TTL=30 seconds
```

The same `application.conf` still works locally because those variables are
optional overrides.

## Deployment Simulation

Deployment artifacts live under `deploy/`:

```text
deploy/pipeline.yml
deploy/kubernetes/namespace.yaml
deploy/kubernetes/configmap.yaml
deploy/kubernetes/redis.yaml
deploy/kubernetes/one-frame.yaml
deploy/kubernetes/forex-mtl.yaml
```

`deploy/pipeline.yml` models a CI/CD flow:

1. run tests and coverage;
2. build the Docker image;
3. push the image to a container registry;
4. apply Kubernetes manifests;
5. update the `forex-mtl` deployment image;
6. wait for rollout completion.

The Kubernetes manifests are intentionally minimal. They are useful for
interview discussion and local cluster experiments, but a production deployment
should add probes, resource requests and limits, Redis persistence via PVC,
network policies, metrics, and a safer secret management mechanism.

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
- Multiple Scala replicas can share the same Redis cache. Refreshes are guarded
  by a Redis `SET NX EX` lock so replicas do not all call One-Frame at the same
  time.
- The Redis refresh lock is TTL-based. If a refresh takes longer than
  `REDIS_REFRESH_LOCK_TTL`, another replica may start a new refresh. In
  production, tune the TTL above the expected refresh duration or move refresh
  work to a dedicated worker.
- The HTTP server starts concurrently with the initial refresh. Requests made
  before that refresh completes receive `RATE_UNAVAILABLE`.
