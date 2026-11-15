package com.ratelimiter.service;

import com.ratelimiter.exception.RateLimiterException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Centralised offload + fail-fast wrapper for every Redis call.
 *
 * <p>Two jobs:
 * <ol>
 *   <li>Run blocking Redis work on a dedicated {@link Executor} so servlet
 *       threads never block (per CLAUDE.md architecture rule).</li>
 *   <li>Catch any exception and re-throw as {@link RateLimiterException} —
 *       never fail-open.</li>
 * </ol>
 *
 * <p>For Jedis-backed limits, use {@link #runJedis(RedisConnectionFactory, Supplier)}
 * which supplies a connection from the pool and runs the lambda on the
 * executor.
 */
public class RedisAdapters {

    private static final Logger log = LoggerFactory.getLogger(RedisAdapters.class);

    /** Fixed-window increment script:
     * KEYS[1] = rate:{clientId}:{windowStartEpoch}
     * ARGV[1] = expire seconds
     * Returns the new counter value. EXPIRE is set only on the first call in a window. */
    public static final DefaultRedisScript<Long> FIXED_WINDOW_SCRIPT;

    /** Sliding-window decision script:
     * KEYS[1] = sliding:{clientId}
     * ARGV[1] = now (ms), ARGV[2] = windowMs, ARGV[3] = limit
     * Returns: [allowed (0|1), used, remaining, retryAfterSeconds, resetEpochSeconds]
     *
     * <p>Lua runs atomically in Redis — a single call replaces ZREMRANGEBYSCORE +
     * ZCARD + ZADD + PEXPIRE, making the decision race-free. */
    public static final DefaultRedisScript<List> SLIDING_WINDOW_SCRIPT;

    /** Token-bucket decision script:
     * KEYS[1] = tokenbucket:{clientId}  (hash: tokens, lastUpdated)
     * ARGV[1] = now (ms), ARGV[2] = maxTokens, ARGV[3] = refillRatePerSecond
     * Returns: [allowed (0|1), floor(tokens), remaining, retryAfterSeconds, resetEpochSeconds] */
    public static final DefaultRedisScript<List> TOKEN_BUCKET_SCRIPT;

    /** Reset script: delete all keys matching the three prefixes for a clientId.
     * KEYS[1..3] = rate:/sliding:/tokenbucket: prefixes.
     * Uses SCAN (safe, non-blocking) then DEL. Returns count of deleted keys. */
    public static final DefaultRedisScript<Long> RESET_SCRIPT;

    static {
        FIXED_WINDOW_SCRIPT = new DefaultRedisScript<>("""
                local current = redis.call('INCR', KEYS[1])
                if current == 1 then
                    redis.call('EXPIRE', KEYS[1], ARGV[1])
                end
                return current
                """, Long.class);

        SLIDING_WINDOW_SCRIPT = new DefaultRedisScript<>("""
                local zset = KEYS[1]
                local now = tonumber(ARGV[1])
                local windowMs = tonumber(ARGV[2])
                local limit = tonumber(ARGV[3])

                -- trim entries older than the window
                redis.call('ZREMRANGEBYSCORE', zset, 0, now - windowMs - 1)
                local used = redis.call('ZCARD', zset)

                local allowed = 0
                local remaining = limit - used
                if remaining < 0 then remaining = 0 end
                local retryAfter = 0
                local resetEpoch = math.floor(now / 1000)

                if used < limit then
                    allowed = 1
                    -- member = "now-<used>" — unique per entry because used is
                    -- monotonically increasing within the window
                    redis.call('ZADD', zset, now, now .. '-' .. tostring(used))
                    redis.call('PEXPIRE', zset, windowMs + 1000)
                else
                    local oldest = redis.call('ZRANGE', zset, 0, 0, 'WITHSCORES')
                    if #oldest >= 2 then
                        local oldestTime = tonumber(oldest[2])
                        local retryMs = (oldestTime + windowMs) - now
                        if retryMs < 0 then retryMs = 0 end
                        retryAfter = math.ceil(retryMs / 1000)
                        if retryAfter < 1 then retryAfter = 1 end
                    end
                end

                return { allowed, used, remaining, retryAfter, resetEpoch }
                """, List.class);

        TOKEN_BUCKET_SCRIPT = new DefaultRedisScript<>("""
                local key = KEYS[1]
                local now = tonumber(ARGV[1])
                local maxTokens = tonumber(ARGV[2])
                local rate = tonumber(ARGV[3])

                local data = redis.call('HMGET', key, 'tokens', 'lastUpdated')
                local tokens = tonumber(data[1])
                local lastUpdated = tonumber(data[2])

                if tokens == nil or lastUpdated == nil then
                    tokens = maxTokens
                    lastUpdated = now
                end

                local elapsedMs = now - lastUpdated
                if elapsedMs > 0 then
                    local refilled = elapsedMs * rate / 1000
                    tokens = math.min(maxTokens, tokens + refilled)
                end

                local allowed = 0
                local remaining = math.floor(tokens)
                local retryAfter = 0
                local resetEpoch = math.floor(now / 1000)

                if tokens >= 1 then
                    allowed = 1
                    tokens = tokens - 1
                    remaining = math.floor(tokens)
                else
                    local waitMs = math.ceil((1 - tokens) * 1000 / rate)
                    if waitMs < 0 then waitMs = 0 end
                    retryAfter = math.ceil(waitMs / 1000)
                    if retryAfter < 1 then retryAfter = 1 end
                end

                redis.call('HSET', key, 'tokens', tokens, 'lastUpdated', now)
                redis.call('EXPIRE', key, math.ceil(maxTokens / rate) * 2 + 10)

                return { allowed, math.floor(tokens), remaining, retryAfter, resetEpoch }
                """, List.class);

        RESET_SCRIPT = new DefaultRedisScript<>("""
                local count = 0
                for _, prefix in ipairs(KEYS) do
                    local cursor = '0'
                    repeat
                        local res = redis.call('SCAN', cursor, 'MATCH', prefix .. '*', 'COUNT', 1000)
                        cursor = res[1]
                        for _, k in ipairs(res[2]) do
                            redis.call('DEL', k)
                            count = count + 1
                        end
                    until cursor == '0'
                end
                return count
                """, Long.class);
    }

    private final RedisTemplate<String, Object> redisTemplate;
    private final RedisConnectionFactory connectionFactory;
    private final Executor executor;
    private final int redisDownRetryAfterSeconds;

    public RedisAdapters(RedisTemplate<String, Object> redisTemplate,
                         RedisConnectionFactory connectionFactory,
                         Executor redisAdaptersExecutor,
                         int redisDownRetryAfterSeconds) {
        this.redisTemplate = redisTemplate;
        this.connectionFactory = connectionFactory;
        this.executor = redisAdaptersExecutor;
        this.redisDownRetryAfterSeconds = redisDownRetryAfterSeconds;
    }

    /**
     * Run a blocking Redis operation off the calling thread and wrap any
     * failure as {@link RateLimiterException} with a retry-after hint.
     *
     * <p>{@code RateLimiterException} thrown inside the supplier propagates
     * as-is (no double-wrapping); all other exceptions are wrapped.
     */
    public <T> CompletableFuture<T> run(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return supplier.get();
            } catch (RateLimiterException ex) {
                throw ex;
            } catch (Exception ex) {
                log.error("Redis call failed; failing fast with 503", ex);
                throw new RateLimiterException("Redis error: " + ex.getMessage(), ex,
                        redisDownRetryAfterSeconds);
            }
        }, executor);
    }

    /** INCR + EXPIRE via the fixed-window script. */
    public CompletableFuture<Long> fixedWindowIncrement(String key, long expireSeconds) {
        return run(() -> redisTemplate.execute(FIXED_WINDOW_SCRIPT, List.of(key), expireSeconds));
    }

    /** Sliding-window decision via the Lua script. */
    public CompletableFuture<List> slidingWindowDecision(String key, long nowMs, long windowMs, int limit) {
        return run(() -> redisTemplate.execute(SLIDING_WINDOW_SCRIPT,
                List.of(key), nowMs, windowMs, limit));
    }

    /** Token-bucket decision via the Lua script. */
    public CompletableFuture<List> tokenBucketDecision(String key, long nowMs,
                                                       int maxTokens, double refillRatePerSecond) {
        return run(() -> redisTemplate.execute(TOKEN_BUCKET_SCRIPT,
                List.of(key), nowMs, maxTokens, refillRatePerSecond));
    }

    /** Current ZSET cardinality (read-only, no decision). */
    public CompletableFuture<Long> zCard(String key) {
        return run(() -> redisTemplate.opsForZSet().zCard(key));
    }

    /** Read a plain string counter value (INCR-based fixed-window keys). */
    public CompletableFuture<Long> getCounter(String key) {
        return run(() -> {
            Object raw = redisTemplate.opsForValue().get(key);
            return raw == null ? 0L : Long.parseLong(raw.toString());
        });
    }

    /** Admin reset across all three key prefixes. */
    public CompletableFuture<Long> resetClient(String clientId) {
        return run(() -> redisTemplate.execute(RESET_SCRIPT,
                List.of("rate:" + clientId + ":", "sliding:" + clientId,
                        "tokenbucket:" + clientId)));
    }

    /** Publish an alert JSON on the ratelimit:alerts channel. */
    public CompletableFuture<Long> publishAlert(String clientId, int used, int limit) {
        return run(() -> {
            String json = String.format(
                    "{\"clientId\":\"%s\",\"used\":%d,\"limit\":%d,\"percentage\":%d}",
                    clientId, used, limit, used * 100 / Math.max(1, limit));
            try (var conn = connectionFactory.getConnection()) {
                return conn.publish("ratelimit:alerts".getBytes(), json.getBytes());
            }
        });
    }
}
