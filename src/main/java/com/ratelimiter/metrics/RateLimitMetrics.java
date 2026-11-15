package com.ratelimiter.metrics;

import com.ratelimiter.model.RateLimitDecision;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Records {@code ratelimit.allowed} / {@code ratelimit.rejected} counters and a
 * per-client sampled gauge {@code ratelimit.current_usage}.
 *
 * <p>The gauge map is bounded: entries are replaced when a client is re-seen
 * and purged when the map exceeds {@code MAX_TRACKED} entries.
 */
@Component
public class RateLimitMetrics {

    private static final int MAX_TRACKED = 10_000;

    private final Counter allowedCounter;
    private final Counter rejectedCounter;
    private final ConcurrentHashMap<String, Long> usageByClient;

    public RateLimitMetrics(MeterRegistry meterRegistry) {
        this.allowedCounter = meterRegistry.counter("ratelimit.allowed");
        this.rejectedCounter = meterRegistry.counter("ratelimit.rejected");
        this.usageByClient = new ConcurrentHashMap<>();

        Gauge.builder("ratelimit.current_usage", usageByClient, ConcurrentHashMap::size)
                .description("Number of distinct clients currently tracked (sampled)")
                .strongReference(true)
                .register(meterRegistry);
    }

    public void record(RateLimitDecision decision, String clientId) {
        if (decision.allowed()) {
            allowedCounter.increment();
        } else {
            rejectedCounter.increment();
        }
        if (usageByClient.size() < MAX_TRACKED) {
            usageByClient.put(clientId, (long) decision.used());
        }
    }

    /**
     * Number of distinct clients currently being tracked (for the admin UI).
     */
    public int trackedClientCount() {
        return usageByClient.size();
    }

    /**
     * Top N clients by usage (for the admin UI).
     */
    public java.util.List<com.ratelimiter.model.TopClient> topClients(int limit, int defaultLimit) {
        return usageByClient.entrySet().stream()
                .sorted(java.util.Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(10)
                .map(e -> new com.ratelimiter.model.TopClient(
                        e.getKey(),
                        e.getValue().intValue(),
                        defaultLimit,
                        Math.max(0, defaultLimit - e.getValue().intValue())))
                .toList();
    }
}
