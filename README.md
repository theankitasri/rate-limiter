# Distributed Rate Limiter

Standalone Spring Boot service that front-mounts any HTTP/Spring Boot application
and enforces per-client rate limits backed by Redis. No blocking Redis calls on
the servlet thread.

## Architecture

```
Client → RateLimitFilter (OncePerRequestFilter, async)
           ↓
      ClientIdentifierResolver chain
        (JWT sub → X-Api-Key → client IP)
           ↓
      RateLimiter (active strategy, selected by ratelimiter.algorithm)
        - FixedWindowRateLimiter      INCR + EXPIRE  O(1)
        - SlidingWindowRateLimiter     ZSET rolling counter
        - TokenBucketRateLimiter       hash of tokens + lastUpdated
           ↓
      RedisAdapters (off-thread executor, fail-fast → 503)
```

## Quick start

```bash
# 1. Start Redis (or point at an existing instance)
docker run -p 6379:6379 redis:7

# 2. Build and run
mvn clean package
java -jar target/distributed-rate-limiter-1.0.0.jar

# 3. Verify
curl -H "X-Api-Key: test-client" http://localhost:8080/ratelimit/check/test-client
curl http://localhost:8080/actuator/health
```

## Configuration

| Property | Default | Description |
|---|---|---|
| `spring.data.redis.host` | `localhost` | Redis host |
| `spring.data.redis.port` | `6379` | Redis port |
| `ratelimiter.default-limit` | `100` | Requests per window |
| `ratelimiter.default-window-seconds` | `60` | Window size (seconds) |
| `ratelimiter.algorithm` | `SLIDING_WINDOW` | `FIXED_WINDOW` \| `SLIDING_WINDOW` \| `TOKEN_BUCKET` |
| `ratelimiter.client` | `LETTUCE` | `LETTUCE` \| `JEDIS` |
| `ratelimiter.retry-after-seconds` | `30` | `Retry-After` value when Redis is down |
| `ratelimiter.jwt-secret` | (env var) | 256-bit HMAC key for JWT client resolution |
| `ratelimiter.admin-users.<user>` | `admin:admin123` | Basic-auth admin credentials |

## REST endpoints

| Method | Path | Auth | Description |
|---|---|---|---|
| `GET` | `/ratelimit/check/{clientId}` | public | Current usage + remaining quota |
| `POST` | `/ratelimit/limit` | admin | Override limits for a client |
| `DELETE` | `/ratelimit/limit/{clientId}` | admin | Reset counter for a client |
| `GET` | `/actuator/health` | public | Health check (includes Redis indicator) |
| `GET` | `/actuator/prometheus` | public | Prometheus metrics |
| `GET` | `/admin` | public | Thymeleaf admin UI |

### 429 response headers

When a request is rate-limited the service responds with `429` and:

```
X-RateLimit-Limit:    100
X-RateLimit-Remaining: 0
X-RateLimit-Reset:    1719400000
Retry-After:          45
```

### 503 response (Redis down)

```
X-Reason:     redis_unavailable
Retry-After:  30
```

## Redis key layout

| Key pattern | Data structure | Algorithm |
|---|---|---|
| `rate:{clientId}:{windowStartEpoch}` | INCR counter (TTL = window) | FIXED_WINDOW |
| `sliding:{clientId}` | ZSET (score = timestamp ms) | SLIDING_WINDOW |
| `tokenbucket:{clientId}` | Hash (`tokens`, `lastUpdated`) | TOKEN_BUCKET |
| `ratelimit:alerts` | Pub/Sub channel | (alert publisher) |

All Lua scripts are atomic — no TOCTOU race between check and increment.

## Production checklist

```bash
# redis.conf (see production/redis.conf)
maxmemory 256mb
maxmemory-policy allkeys-lfu
```

Set `JWT_SECRET` env var to a random 256-bit value. Change
`ADMIN_PASSWORD` for basic-auth admin access.

## Load test

```bash
# Option A: k6 (preferred — lightweight)
k6 run -e BASE_URL=http://localhost:8080 load-test/rate-limiter-load.js

# Option B: JMeter
bash load-test/run-load-test.sh jmeter

# Target: ≥ 10,000 sustained req/s
```

## Run unit tests

```bash
# Tests use embedded Redis on port 63799 — no external Redis required
mvn test
```

Scenarios covered:
1. Fixed window resets at boundary
2. Sliding window enforces rolling limit (no burst at window edge)
3. Concurrent requests never exceed the limit
