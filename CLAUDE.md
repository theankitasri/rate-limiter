# CLAUDE.md

Guidance for Claude Code working in this repository.

## Project

Distributed rate limiter as a standalone Spring Boot service. Front-mounts any Spring Boot app (or any HTTP service) and enforces per-client rate limits backed by Redis.

## Build & Test

```bash
# Full build (tests included; skip -DskipTests in CI-quick iterations)
mvn clean package

# Run the service
mvn spring-boot:run

# Single test class
mvn test -Dtest=FixedWindowRateLimiterTest

# Load test (10k req/s proof)
bash load-test/run-load-test.sh
```

No frontend to test in a browser — the only "UI" is Thymeleaf admin pages (`/admin`). Verify behavior via `curl` against the endpoints in `README.md` §Testing.

## Architecture (non-obvious parts)

- **No Redis calls on the servlet thread.** The `RateLimitFilter` (a `OncePerRequestFilter` with async dispatch) wraps every Redis operation in `CompletableFuture`; the thread returns via `chain.doFilter` and the actual decision runs on the `RedisAdapters` executor. Reject path calls `chain.doFilter` again after emitting headers — do not add blocking Redis calls to the filter.
- **Fail-fast on Redis errors.** `RateLimiterException` (wraps any Redis failure) → 503 + `Retry-After`. Never fail-open.
- **Two algorithm strategies** behind `RateLimiter` interface, selected at runtime by `ratelimiter.algorithm`:
  - `FixedWindowRateLimiter`: `INCR` on `rate:{clientId}:{windowStartEpoch}` + `EXPIRE`. O(1).
  - `SlidingWindowRateLimiter`: ZSET `sliding:{clientId}` (score = timestamp ms), trimmed with `ZREMRANGEBYSCORE`.
  - Both are Redis-backed only; no local cache layer.
- **ClientIdentifierResolver** chain: JWT sub → `X-Api-Key` header → fallback to client IP. New sources = new implementation of the interface + register a Bean.
- **Redis client is swappable** (`ratelimiter.client=LETTUCE|JEDIS`); default LETTUCE. Jedis pool config under `ratelimiter.jedis.*` (`max-total` default 50, `max-idle` 10, `min-idle` 5, `block-if-exhausted` true).

## Key packages

| Package | Responsibility |
|---|---|
| `com.ratelimiter.service` | `RateLimiter` interface + implementations |
| `com.ratelimiter.resolver` | `ClientIdentifierResolver` + implementations |
| `com.ratelimiter.filter` | `RateLimitFilter` (OncePerRequestFilter, async) |
| `com.ratelimiter.controller` | REST API (`/ratelimit/**`) + admin Thymeleaf pages |
| `com.ratelimiter.configuration` | Redis client selection (Lettuce vs Jedis pool), JWT + Basic auth |
| `com.ratelimiter.metrics` | `ratelimit.allowed` / `ratelimit.rejected` counters, per-client gauge sampling |
| `com.ratelimiter.health` | Custom `RedisHealthIndicator` for `/actuator/health` |

## Conventions

- Javadoc on public classes/methods (user-facing API: `RateLimiter`, `ClientIdentifierResolver`, filter, controllers).
- `log.error` with exception object (stack trace) when Redis fails; never log at DEBUG in the hot filter path.
- All Redis keys follow the documented prefixes: `rate:` (fixed), `sliding:` (sliding), `ratelimit:alerts` (pub/sub channel), `ratelimit:config:` (admin overrides).

## Testing

- Unit tests use **Embedded Redis** (`it.ozimov:embedded-redis`), not mocks. See `src/test/java/.../support/EmbeddedRedisExtension.java`.
- The three required scenarios are:
  1. Fixed window resets at boundary
  2. Sliding window enforces rolling limit (no burst at window edge)
  3. Concurrent requests never exceed the limit
- Load test target: ≥ 10,000 req/s. `load-test/` has a JMeter `.jmx` and a `k6` script; both hit the default window.

## Production notes

- `redis.conf` must set `maxmemory` (e.g. 256mb) and `maxmemory-policy allkeys-lfu` — see `README.md` §Production.
- Prometheus export: `management.metrics.export.prometheus.enabled=true` (already in `application.yml`).
- Alerts channel `ratelimit:alerts`: published when a client reaches 80% of its limit (via `RateLimitAlertPublisher`, triggered from the filter's reject/80% paths).
