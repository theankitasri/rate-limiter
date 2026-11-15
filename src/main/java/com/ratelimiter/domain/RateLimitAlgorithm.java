package com.ratelimiter.domain;

/**
 * Rate limiting algorithm strategies. Selected at runtime via
 * {@code ratelimiter.algorithm} — no restart required for limit changes,
 * only for algorithm switching (the active implementation is a Spring bean
 * chosen at startup; limit values read from Redis on every call).
 */
public enum RateLimitAlgorithm {
    /** INCR + EXPIRE on a per-window key. O(1), resets at the window boundary. */
    FIXED_WINDOW,
    /** Redis ZSET rolling counter. Higher accuracy, no burst at window edges. */
    SLIDING_WINDOW,
    /** Token bucket: hash of tokens + lastUpdated, refilled on each call. */
    TOKEN_BUCKET
}
