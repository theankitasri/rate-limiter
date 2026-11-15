package com.ratelimiter.service;

import com.ratelimiter.model.RateLimitDecision;
import com.ratelimiter.model.RateLimitStatus;

import java.util.concurrent.CompletableFuture;

/**
 * Strategy interface for distributed rate limiting backed by Redis.
 *
 * <p>Implementations must be non-blocking: every method returns a
 * {@link CompletableFuture} so callers never wait on Redis I/O. The concrete
 * executor used for that offload is injected via the constructor.
 */
public interface RateLimiter {

    /**
     * Evaluate a single request against the given limit and time window.
     *
     * @param clientId     the client identifier (resolved by a {@code ClientIdentifierResolver})
     * @param limit        maximum allowed requests within the window
     * @param windowSeconds window length in seconds
     * @return decision; completes exceptionally with a
     *         {@link com.ratelimiter.exception.RateLimiterException} on Redis failure
     */
    CompletableFuture<RateLimitDecision> allowRequest(String clientId, int limit, int windowSeconds);

    /**
     * Read current usage without consuming a request.
     */
    CompletableFuture<RateLimitStatus> currentUsage(String clientId, int limit, int windowSeconds);

    /**
     * Reset all counters for a client (used by
     * {@code DELETE /ratelimit/limit/{clientId}}).
     */
    CompletableFuture<Void> reset(String clientId);

    /**
     * The algorithm this implementation uses.
     */
    String algorithmName();
}
