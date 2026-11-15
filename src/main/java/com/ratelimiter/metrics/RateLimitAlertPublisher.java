package com.ratelimiter.metrics;

import com.ratelimiter.service.RedisAdapters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Publishes an alert on the {@code ratelimit:alerts} Redis pub/sub channel
 * when a client reaches the configured percentage threshold of its limit.
 *
 * <p>De-duplication: one alert per client per evaluation cycle — callers
 * should check {@link #shouldAlert(String, int, int)} before invoking
 * {@link #publish(String, int, int)}.
 */
@Component
public class RateLimitAlertPublisher {

    private static final Logger log = LoggerFactory.getLogger(RateLimitAlertPublisher.class);
    private static final int THRESHOLD_PERCENT = 80;

    private final RedisAdapters redis;

    public RateLimitAlertPublisher(RedisAdapters redis) {
        this.redis = redis;
    }

    /**
     * @return true if the client has just crossed the 80% threshold
     *         (used &lt; threshold in the previous check is not tracked —
     *         this is a fire-and-forget, once-per-window heuristic)
     */
    public boolean shouldAlert(int used, int limit) {
        return used > 0 && limit > 0 && (used * 100) / limit >= THRESHOLD_PERCENT
                && used <= limit;
    }

    public void publish(String clientId, int used, int limit) {
        if (!shouldAlert(used, limit)) {
            return;
        }
        log.info("Rate limit alert: client {} reached {}% of limit ({}/{})",
                clientId, (used * 100) / limit, used, limit);
        redis.publishAlert(clientId, used, limit)
                .exceptionally(ex -> {
                    log.error("Failed to publish rate limit alert for client {}", clientId, ex);
                    return null;
                });
    }
}
