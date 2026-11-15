package com.ratelimiter.health;

import com.ratelimiter.service.RedisAdapters;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

/**
 * Custom health indicator that reports Redis availability.
 *
 * <p>Overlaps with Spring Boot's built-in {@code RedisHealthIndicator} but
 * adds the {@code ratelimiter.*} detail keys so dashboards can distinguish
 * the rate-limiter's Redis from any other Redis the app connects to.
 */
@Component("redisHealth")
public class RedisHealthIndicator implements HealthIndicator {

    private final RedisConnectionFactory connectionFactory;

    public RedisHealthIndicator(RedisConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public Health health() {
        try (var conn = connectionFactory.getConnection()) {
            conn.ping();
            return Health.up()
                    .withDetail("redis.ping", "ok")
                    .withDetail("ratelimiter.fail-fast", true)
                    .build();
        } catch (Exception ex) {
            return Health.down(ex)
                    .withDetail("redis.error", ex.getMessage())
                    .withDetail("ratelimiter.fail-fast", true)
                    .build();
        }
    }
}
