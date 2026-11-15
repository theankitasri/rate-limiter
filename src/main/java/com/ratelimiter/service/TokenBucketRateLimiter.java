package com.ratelimiter.service;

import com.ratelimiter.domain.RateLimitAlgorithm;
import com.ratelimiter.model.RateLimitDecision;
import com.ratelimiter.model.RateLimitStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Optional token-bucket strategy.
 *
 * <p>State is stored in a Redis hash {@code tokenbucket:{clientId}} with
 * fields {@code tokens} (double) and {@code lastUpdated} (ms). Tokens are
 * refilled on every call based on elapsed time and
 * {@code ratelimiter.token-bucket.refill-rate-per-second}. Capacity defaults
 * to the configured limit.
 */
@Component("tokenBucketRateLimiter")
public class TokenBucketRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(TokenBucketRateLimiter.class);

    private final RedisAdapters redis;
    private final int defaultLimit;
    private final int defaultWindowSeconds;
    private final double refillRatePerSecond;

    public TokenBucketRateLimiter(RedisAdapters redis,
                                  @Value("${ratelimiter.default-limit:100}") int defaultLimit,
                                  @Value("${ratelimiter.default-window-seconds:60}") int defaultWindowSeconds,
                                  @Value("${ratelimiter.token-bucket.refill-rate-per-second:}")
                                  double refillRatePerSecond) {
        this.redis = redis;
        this.defaultLimit = defaultLimit;
        this.defaultWindowSeconds = defaultWindowSeconds;
        this.refillRatePerSecond = refillRatePerSecond > 0
                ? refillRatePerSecond
                : defaultLimit / (double) Math.max(1, defaultWindowSeconds);
    }

    private String keyFor(String clientId) {
        return "tokenbucket:" + clientId;
    }

    @Override
    public CompletableFuture<RateLimitDecision> allowRequest(String clientId, int limit, int windowSeconds) {
        int effectiveLimit = limit <= 0 ? defaultLimit : limit;
        double rate = limit <= 0 ? refillRatePerSecond : limit / (double) Math.max(1, windowSeconds);
        long nowMs = System.currentTimeMillis();

        return redis.tokenBucketDecision(keyFor(clientId), nowMs, effectiveLimit, rate)
                .thenApply(results -> {
                    int allowedFlag = toInt(results.get(0));
                    int used = effectiveLimit - toInt(results.get(2));
                    int remaining = toInt(results.get(2));
                    int retryAfter = toInt(results.get(3));
                    long resetEpoch = toLong(results.get(4));
                    boolean allowed = allowedFlag == 1;
                    if (!allowed) {
                        log.debug("Token bucket: client {} rejected ({}/{})", clientId, used, effectiveLimit);
                    }
                    return allowed
                            ? RateLimitDecision.allowed(effectiveLimit, used, remaining, 0, resetEpoch)
                            : RateLimitDecision.rejected(effectiveLimit, used, remaining, retryAfter, resetEpoch);
                });
    }

    @Override
    public CompletableFuture<RateLimitStatus> currentUsage(String clientId, int limit, int windowSeconds) {
        int effectiveLimit = limit <= 0 ? defaultLimit : limit;
        int window = windowSeconds <= 0 ? defaultWindowSeconds : windowSeconds;
        double rate = limit <= 0 ? refillRatePerSecond : limit / (double) Math.max(1, window);
        long nowMs = System.currentTimeMillis();

        return redis.tokenBucketDecision(keyFor(clientId), nowMs, effectiveLimit, rate)
                .thenApply(results -> {
                    int remaining = toInt(results.get(2));
                    int used = effectiveLimit - remaining;
                    if (used < 0) used = 0;
                    return new RateLimitStatus(clientId, effectiveLimit, used, remaining,
                            window, RateLimitAlgorithm.TOKEN_BUCKET);
                });
    }

    @Override
    public CompletableFuture<Void> reset(String clientId) {
        return redis.resetClient(clientId).thenApply(v -> null);
    }

    @Override
    public String algorithmName() {
        return "TOKEN_BUCKET";
    }

    private static int toInt(Object v) {
        return v == null ? 0 : ((Number) v).intValue();
    }

    private static long toLong(Object v) {
        return v == null ? 0L : ((Number) v).longValue();
    }
}
