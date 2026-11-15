package com.ratelimiter.filter;

import com.ratelimiter.exception.RateLimiterException;
import com.ratelimiter.metrics.RateLimitAlertPublisher;
import com.ratelimiter.metrics.RateLimitMetrics;
import com.ratelimiter.model.RateLimitDecision;
import com.ratelimiter.resolver.ClientIdentifierResolver;
import com.ratelimiter.service.RateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;

/**
 * Core request interceptor. Registered via {@link
 * com.ratelimiter.configuration.FilterRegistrationConfiguration} as an
 * async-capable servlet filter (Servlet spec §7.7.3).
 *
 * <p>Flow:
 * <ol>
 *   <li>{@code doFilterInternal} calls {@code request.startAsync()},
 *       which releases the servlet thread. It registers the
 *       {@code CompletableFuture} callbacks and returns.</li>
 *   <li>The decision callback sets a re-entrance guard attribute and calls
 *       {@code asyncContext.dispatch()}, causing the container to
 *       re-enter the filter chain on a servlet thread.</li>
 *   <li>The re-entrant call hits the guard in {@link #shouldNotFilter},
 *       the guard also checks for a Redis-down attribute, and either
 *       writes 503 or forwards to {@code chain.doFilter}.</li>
 * </ol>
 *
 * <p>On {@link RateLimiterException} (Redis down) the filter writes 503 +
 * {@code Retry-After} and does not continue the chain (fail-fast, no
 * fail-open).
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RateLimiter rateLimiter;
    private final List<ClientIdentifierResolver> resolvers;
    private final RateLimitMetrics metrics;
    private final RateLimitAlertPublisher alertPublisher;
    private final int defaultLimit;
    private final int defaultWindowSeconds;
    private static final String ASYNC_REDIS_DOWN = "ratelimiter.async_redis_down";
    private static final String ASYNC_REDIS_RETRY = "ratelimiter.async_redis_retry";

    public RateLimitFilter(RateLimiter rateLimiter,
                           List<ClientIdentifierResolver> resolvers,
                           RateLimitMetrics metrics,
                           RateLimitAlertPublisher alertPublisher,
                           @Value("${ratelimiter.default-limit:100}") int defaultLimit,
                           @Value("${ratelimiter.default-window-seconds:60}") int defaultWindowSeconds) {
        this.rateLimiter = rateLimiter;
        this.resolvers = resolvers.stream()
                .sorted(Comparator.comparingInt(ClientIdentifierResolver::order))
                .toList();
        this.metrics = metrics;
        this.alertPublisher = alertPublisher;
        this.defaultLimit = defaultLimit;
        this.defaultWindowSeconds = defaultWindowSeconds;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (Boolean.TRUE.equals(request.getAttribute("__ratelimit_async_complete"))) {
            return true;
        }
        String path = request.getServletPath();
        return path.startsWith("/actuator")
                || path.startsWith("/admin")
                || path.equals("/error")
                || path.startsWith("/css")
                || path.startsWith("/js");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // Re-entrant pass: complete the async response (503 if Redis down,
        // otherwise forward to the app).
        if (Boolean.TRUE.equals(request.getAttribute("__ratelimit_async_complete"))) {
            Object attrRetry = request.getAttribute(ASYNC_REDIS_RETRY);
            if (Boolean.TRUE.equals(request.getAttribute(ASYNC_REDIS_DOWN))) {
                int retryAfter = (attrRetry instanceof Integer i) ? i : defaultWindowSeconds;
                writeRedisDownResponse(response, retryAfter);
                request.getAsyncContext().complete();
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }

        String clientId = resolveClientId(request);
        log.debug("Rate limit check for client {}", clientId);

        var asyncContext = request.startAsync(request, response);
        asyncContext.setTimeout(5_000);

        rateLimiter.allowRequest(clientId, defaultLimit, defaultWindowSeconds)
                .thenAccept(decision -> {
                    try {
                        metrics.record(decision, clientId);
                        alertPublisher.publish(clientId, decision.used(), decision.limit());
                        setRateLimitHeaders(response, decision);

                        if (decision.allowed()) {
                            request.setAttribute("__ratelimit_async_complete", Boolean.TRUE);
                            asyncContext.dispatch();
                        } else {
                            log.debug("Rate limit rejected: client {} (used {}/{})",
                                    clientId, decision.used(), decision.limit());
                            response.setHeader("Retry-After",
                                    String.valueOf(decision.retryAfterSeconds()));
                            writeJson429(response, decision);
                            request.getAsyncContext().complete();
                        }
                    } catch (Exception ex) {
                        log.error("Error completing rate limit decision for client {}",
                                clientId, ex);
                        if (!response.isCommitted()) {
                            writeRedisDownResponse(response, defaultWindowSeconds);
                        }
                        request.getAsyncContext().complete();
                    }
                })
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof RateLimiterException rle) {
                        log.error("Redis unavailable; returning 503 for client {}", clientId, rle);
                    } else {
                        log.error("Unexpected error in rate limit evaluation for client {}",
                                clientId, cause);
                    }
                    int retryAfter = (cause instanceof RateLimiterException rle)
                            ? rle.getRetryAfterSeconds()
                            : defaultWindowSeconds;
                    Object existing = request.getAttribute(ASYNC_REDIS_RETRY);
                    request.setAttribute("__ratelimit_async_complete", Boolean.TRUE);
                    request.setAttribute(ASYNC_REDIS_DOWN, Boolean.TRUE);
                    request.setAttribute(ASYNC_REDIS_RETRY,
                            existing != null ? existing : retryAfter);
                    asyncContext.dispatch();
                    return null;
                });
    }

    private void setRateLimitHeaders(HttpServletResponse response, RateLimitDecision decision) {
        response.setHeader("X-RateLimit-Limit", String.valueOf(decision.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
        response.setHeader("X-RateLimit-Reset", String.valueOf(decision.resetEpochSeconds()));
    }

    private void writeJson429(HttpServletResponse response, RateLimitDecision decision) {
        response.setStatus(429);
        response.setContentType("application/json");
        try {
            String body = String.format(
                    "{\"error\":\"rate_limit_exceeded\",\"limit\":%d,\"used\":%d,\"retryAfterSeconds\":%d}",
                    decision.limit(), decision.used(), decision.retryAfterSeconds());
            response.getWriter().write(body);
        } catch (IOException ex) {
            log.error("Failed to write 429 body", ex);
        }
    }

    private void writeRedisDownResponse(HttpServletResponse response, int retryAfterSeconds) {
        response.setStatus(503);
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setHeader("X-Reason", "redis_unavailable");
        response.setContentType("application/json");
        try {
            response.getWriter().write(
                    "{\"error\":\"service_unavailable\",\"reason\":\"redis_unavailable\",\"retryAfterSeconds\":"
                    + retryAfterSeconds + "}");
        } catch (IOException ex) {
            log.error("Failed to write 503 body", ex);
        }
    }

    private String resolveClientId(HttpServletRequest request) {
        for (ClientIdentifierResolver resolver : resolvers) {
            String id = resolver.resolve(request);
            if (id != null && !id.isBlank()) {
                return id;
            }
        }
        return "ip:" + request.getRemoteAddr();
    }
}
