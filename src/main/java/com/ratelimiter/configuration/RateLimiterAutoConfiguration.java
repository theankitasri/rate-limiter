package com.ratelimiter.configuration;

import com.ratelimiter.domain.RateLimitAlgorithm;
import com.ratelimiter.service.FixedWindowRateLimiter;
import com.ratelimiter.service.RateLimiter;
import com.ratelimiter.service.SlidingWindowRateLimiter;
import com.ratelimiter.service.TokenBucketRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Selects the active {@link RateLimiter} implementation at startup based
 * on {@code ratelimiter.algorithm} and marks it {@code @Primary} so it
 * is the one injected wherever a single {@code RateLimiter} is required
 * (e.g. {@link com.ratelimiter.filter.RateLimitFilter}).
 *
 * <p>Uses {@link RateLimiterProperties} directly rather than relying on
 * a {@code RateLimitAlgorithm} bean (which would require the
 * {@code -parameters} compiler flag for constructor name retention).
 */
@Configuration
public class RateLimiterAutoConfiguration {

    @Bean
    @Primary
    public RateLimiter primaryRateLimiter(RateLimiterProperties properties,
                                          FixedWindowRateLimiter fixedWindowRateLimiter,
                                          SlidingWindowRateLimiter slidingWindowRateLimiter,
                                          TokenBucketRateLimiter tokenBucketRateLimiter) {
        RateLimitAlgorithm algorithm = properties.getAlgorithm();
        return switch (algorithm) {
            case FIXED_WINDOW -> fixedWindowRateLimiter;
            case SLIDING_WINDOW -> slidingWindowRateLimiter;
            case TOKEN_BUCKET -> tokenBucketRateLimiter;
        };
    }
}
