package com.ratelimiter.service;

import com.ratelimiter.domain.RateLimitAlgorithm;
import com.ratelimiter.model.RateLimitDecision;
import com.ratelimiter.model.RateLimitStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * Fixed-window rate limiter using Redis {@code INCR + EXPIRE} (atomic Lua script).
 *
 * <p>Key: {@code rate:{clientId}:{windowStartEpoch}} where {@code windowStartEpoch}
 * is the window boundary in seconds. O(1) per call. Resets hard at the window
 * boundary — a burst just before the boundary plus a burst just after it can
 * momentarily allow up to {@code 2 * limit} requests, which is acceptable for
 * most use-cases but not for strict fairness (use {@link SlidingWindowRateLimiter}).
 */
@Component("fixedWindowRateLimiter")
public class FixedWindowRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(FixedWindowRateLimiter.class);

    private final RedisAdapters redis;
    private final int defaultLimit;
    private final int defaultWindowSeconds;

    public FixedWindowRateLimiter(RedisAdapters redis,
                                  @Value("${ratelimiter.default-limit:100}") int defaultLimit,
                                  @Value("${ratelimiter.default-window-seconds:60}") int defaultWindowSeconds) {
        this.redis = redis;
        this.defaultLimit = defaultLimit;
        this.defaultWindowSeconds = defaultWindowSeconds;
    }

    private long windowStartEpoch(int windowSeconds) {
        int window = windowSeconds <= 0 ? defaultWindowSeconds : windowSeconds;
        return (Instant.now().getEpochSecond() / window) * window;
    }

    private String keyFor(String clientId, int windowSeconds) {
        return "rate:" + clientId + ":" + windowStartEpoch(windowSeconds);
    }

    @Override
    public CompletableFuture<RateLimitDecision> allowRequest(String clientId, int limit, int windowSeconds) {
        int effectiveLimit = limit <= 0 ? defaultLimit : limit;
        int window = windowSeconds <= 0 ? defaultWindowSeconds : windowSeconds;
        long windowStartEpoch = windowStartEpoch(window);
        String key = keyFor(clientId, window);

        return redis.fixedWindowIncrement(key, window)
                .thenApply(counter -> {
                    int used = counter.intValue();
                    int remaining = Math.max(0, effectiveLimit - used);
                    long resetEpoch = windowStartEpoch + window;
                    boolean allowed = used <= effectiveLimit;
                    if (!allowed) {
                        log.debug("Fixed window: client {} rejected ({}/{})",
                                clientId, used, effectiveLimit);
                    }
                    int retryAfter = allowed ? 0
                            : (int) Math.max(1, resetEpoch - Instant.now().getEpochSecond());
                    return allowed
                            ? RateLimitDecision.allowed(effectiveLimit, used, remaining, 0, resetEpoch)
                            : RateLimitDecision.rejected(effectiveLimit, used, remaining,
                                    retryAfter, resetEpoch);
                });
    }

    @Override
    public CompletableFuture<RateLimitStatus> currentUsage(String clientId, int limit, int windowSeconds) {
        int effectiveLimit = limit <= 0 ? defaultLimit : limit;
        int window = windowSeconds <= 0 ? defaultWindowSeconds : windowSeconds;
        String key = keyFor(clientId, window);

        return redis.getCounter(key)
                .thenApply(used -> {
                    int usedInt = used.intValue();
                    int remaining = Math.max(0, effectiveLimit - usedInt);
                    return new RateLimitStatus(clientId, effectiveLimit, usedInt, remaining,
                            window, RateLimitAlgorithm.FIXED_WINDOW);
                });
    }

    @Override
    public CompletableFuture<Void> reset(String clientId) {
        return redis.resetClient(clientId).thenApply(v -> null);
    }

    @Override
    public String algorithmName() {
        return "FIXED_WINDOW";
    }

    public int defaultLimit() {
        return defaultLimit;
    }

    public int defaultWindowSeconds() {
        return defaultWindowSeconds;
    }
}
