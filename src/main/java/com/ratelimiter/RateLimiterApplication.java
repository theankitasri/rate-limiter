package com.ratelimiter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point for the distributed rate limiter service.
 *
 * <p>This service is front-mounted in front of any Spring Boot (or HTTP) application:
 * all incoming requests pass through {@code RateLimitFilter}, which enforces
 * per-client limits backed by Redis. No blocking Redis calls occur on the
 * servlet thread — decisions are made on the {@code RedisAdapters} executor.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class RateLimiterApplication {

    public static void main(String[] args) {
        SpringApplication.run(RateLimiterApplication.class, args);
    }
}
