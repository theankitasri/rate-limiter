package com.ratelimiter.exception;

/**
 * Wraps any Redis failure encountered while evaluating a rate limit decision.
 *
 * <p>Policy: fail-fast. The mapper (filter or controller) responds with HTTP 503
 * and a {@code Retry-After} header; it must never fail-open and allow all
 * traffic through when Redis is unavailable.
 */
public class RateLimiterException extends RuntimeException {

    /** Suggested value (seconds) for the HTTP {@code Retry-After} response header. */
    private final int retryAfterSeconds;

    public RateLimiterException(String message, Throwable cause, int retryAfterSeconds) {
        super(message, cause);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
