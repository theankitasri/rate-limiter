package com.ratelimiter.model;

import com.ratelimiter.domain.RateLimitAlgorithm;

/**
 * Read-only snapshot of a client's current usage (used by
 * {@code GET /ratelimit/check/{clientId}}).
 */
public record RateLimitStatus(String clientId, int limit, int used, int remaining,
                              int windowSeconds, RateLimitAlgorithm algorithm) {
}
