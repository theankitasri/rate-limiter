package com.ratelimiter.model;

/**
 * Outcome of a rate limit evaluation.
 */
public record RateLimitDecision(boolean allowed, int limit, int used, int remaining,
                               int retryAfterSeconds, long resetEpochSeconds) {

    public static RateLimitDecision allowed(int limit, int used, int remaining,
                                            int retryAfterSeconds, long resetEpochSeconds) {
        return new RateLimitDecision(true, limit, used, remaining, retryAfterSeconds, resetEpochSeconds);
    }

    public static RateLimitDecision rejected(int limit, int used, int remaining,
                                             int retryAfterSeconds, long resetEpochSeconds) {
        return new RateLimitDecision(false, limit, used, remaining, retryAfterSeconds, resetEpochSeconds);
    }
}
