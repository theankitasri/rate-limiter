package com.ratelimiter.service;

import com.ratelimiter.domain.RateLimitAlgorithm;
import com.ratelimiter.model.RateLimitDecision;
import com.ratelimiter.model.RateLimitStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Sliding-window rate limiter using a Redis ZSET ({@code sliding:{clientId}}).
 *
 * <p>Each allowed request adds a member with the current timestamp (ms) as
 * score. The decision runs as a single atomic Lua script:
 * {@code ZREMRANGEBYSCORE} trims entries older than the window, then
 * {@code ZCARD} compares against the limit and {@code ZADD} records the new
 * entry only when allowed. No burst at the window edge — the counter rolls
 * smoothly.
 */
@Component("slidingWindowRateLimiter")
public class SlidingWindowRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(SlidingWindowRateLimiter.class);

    private final RedisAdapters redis;
    private final int defaultLimit;
    private final int defaultWindowSeconds;

    public SlidingWindowRateLimiter(RedisAdapters redis,
                                    @Value("${ratelimiter.default-limit:100}") int defaultLimit,
                                    @Value("${ratelimiter.default-window-seconds:60}")
                                    int defaultWindowSeconds) {
        this.redis = redis;
        this.defaultLimit = defaultLimit;
        this.defaultWindowSeconds = defaultWindowSeconds;
    }

    private String keyFor(String clientId) {
        return "sliding:" + clientId;
    }

    @Override
    public CompletableFuture<RateLimitDecision> allowRequest(String clientId, int limit, int windowSeconds) {
        int effectiveLimit = limit <= 0 ? defaultLimit : limit;
        int window = windowSeconds <= 0 ? defaultWindowSeconds : windowSeconds;
        long nowMs = System.currentTimeMillis();
        String key = keyFor(clientId);

        return redis.slidingWindowDecision(key, nowMs, (long) window * 1000, effectiveLimit)
                .thenApply(results -> interpret(results, clientId, effectiveLimit));
    }

    @SuppressWarnings("unchecked")
    private RateLimitDecision interpret(List<?> raw, String clientId, int limit) {
        List<Object> results = (List<Object>) raw;
        int allowedFlag = toInt(results.get(0));
        int used = toInt(results.get(1));
        int remaining = toInt(results.get(2));
        int retryAfter = toInt(results.get(3));
        long resetEpoch = toLong(results.get(4));
        boolean allowed = allowedFlag == 1;
        if (!allowed) {
            log.debug("Sliding window: client {} rejected ({}/{})", clientId, used, limit);
        }
        return allowed
                ? RateLimitDecision.allowed(limit, used, remaining, 0, resetEpoch)
                : RateLimitDecision.rejected(limit, used, remaining, retryAfter, resetEpoch);
    }

    @Override
    public CompletableFuture<RateLimitStatus> currentUsage(String clientId, int limit, int windowSeconds) {
        int effectiveLimit = limit <= 0 ? defaultLimit : limit;
        int window = windowSeconds <= 0 ? defaultWindowSeconds : windowSeconds;
        String key = keyFor(clientId);

        return redis.zCard(key)
                .thenApply(used -> {
                    int remaining = Math.max(0, effectiveLimit - used.intValue());
                    return new RateLimitStatus(clientId, effectiveLimit, used.intValue(),
                            remaining, window, RateLimitAlgorithm.SLIDING_WINDOW);
                });
    }

    @Override
    public CompletableFuture<Void> reset(String clientId) {
        return redis.resetClient(clientId).thenApply(v -> null);
    }

    @Override
    public String algorithmName() {
        return "SLIDING_WINDOW";
    }

    public int defaultLimit() {
        return defaultLimit;
    }

    public int defaultWindowSeconds() {
        return defaultWindowSeconds;
    }

    private static int toInt(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) return Integer.parseInt(s);
        return 0;
    }

    private static long toLong(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) return Long.parseLong(s);
        return 0L;
    }
}
