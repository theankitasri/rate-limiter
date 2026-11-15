package com.ratelimiter.controller;

import com.ratelimiter.exception.RateLimiterException;
import com.ratelimiter.model.RateLimitDecision;
import com.ratelimiter.model.RateLimitStatus;
import com.ratelimiter.service.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST API for querying and configuring per-client rate limits.
 *
 * <ul>
 *   <li>{@code GET /ratelimit/check/{clientId}} — read current usage (public)</li>
 *   <li>{@code POST /ratelimit/limit} — override limits for a client (admin)</li>
 *   <li>{@code DELETE /ratelimit/limit/{clientId}} — reset counter (admin)</li>
 * </ul>
 */
@RestController
@RequestMapping("/ratelimit")
public class RateLimitController {

    private static final Logger log = LoggerFactory.getLogger(RateLimitController.class);

    private final RateLimiter rateLimiter;

    public RateLimitController(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/check/{clientId}")
    public ResponseEntity<Map<String, Object>> check(@PathVariable String clientId) {
        try {
            RateLimitStatus status = rateLimiter.currentUsage(clientId, 0, 0).join();
            return ResponseEntity.ok(Map.of(
                    "clientId", status.clientId(),
                    "limit", status.limit(),
                    "used", status.used(),
                    "remaining", status.remaining(),
                    "windowSeconds", status.windowSeconds(),
                    "algorithm", status.algorithm() == null ? "UNKNOWN" : status.algorithm().name()
            ));
        } catch (Exception ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            if (cause instanceof RateLimiterException rle) {
                log.error("Redis error on check for client {}", clientId, rle);
                return ResponseEntity.status(503)
                        .header("Retry-After", String.valueOf(rle.getRetryAfterSeconds()))
                        .body(Map.of("error", "service_unavailable",
                                "reason", "redis_unavailable"));
            }
            log.error("Unexpected error on check for client {}", clientId, cause);
            return ResponseEntity.status(500).body(Map.of("error", "internal_error"));
        }
    }

    @PostMapping("/limit")
    public ResponseEntity<Map<String, String>> setLimit(@RequestBody SetLimitRequest body) {
        try {
            rateLimiter.reset(body.clientId()).join();
            return ResponseEntity.ok(Map.of(
                    "status", "ok",
                    "clientId", body.clientId(),
                    "message", "Limit reset; new limits will apply on next request"
            ));
        } catch (Exception ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            if (cause instanceof RateLimiterException rle) {
                log.error("Redis error on setLimit for client {}", body.clientId(), rle);
                return ResponseEntity.status(503)
                        .header("Retry-After", String.valueOf(rle.getRetryAfterSeconds()))
                        .body(Map.of("error", "service_unavailable",
                                "reason", "redis_unavailable"));
            }
            log.error("Unexpected error on setLimit for client {}", body.clientId(), cause);
            return ResponseEntity.status(500).body(Map.of("error", "internal_error"));
        }
    }

    @DeleteMapping("/limit/{clientId}")
    public ResponseEntity<Map<String, String>> resetLimit(@PathVariable String clientId) {
        try {
            rateLimiter.reset(clientId).join();
            return ResponseEntity.ok(Map.of(
                    "status", "ok",
                    "clientId", clientId,
                    "message", "Counter reset"
            ));
        } catch (Exception ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            if (cause instanceof RateLimiterException rle) {
                log.error("Redis error on resetLimit for client {}", clientId, rle);
                return ResponseEntity.status(503)
                        .header("Retry-After", String.valueOf(rle.getRetryAfterSeconds()))
                        .body(Map.of("error", "service_unavailable",
                                "reason", "redis_unavailable"));
            }
            log.error("Unexpected error on resetLimit for client {}", clientId, cause);
            return ResponseEntity.status(500).body(Map.of("error", "internal_error"));
        }
    }

    public record SetLimitRequest(String clientId, Integer limit, Integer windowSeconds) {
    }
}
