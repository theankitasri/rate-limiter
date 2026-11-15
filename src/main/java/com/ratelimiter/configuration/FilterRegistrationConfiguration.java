package com.ratelimiter.configuration;

import com.ratelimiter.filter.RateLimitFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link RateLimitFilter} as an async-capable servlet filter so
 * the {@code CompletableFuture} decision callback can safely call
 * {@code asyncContext.dispatch()} and write the response on a servlet
 * thread (Servlet spec §7.7.3).
 */
@Configuration
public class FilterRegistrationConfiguration {

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(
            RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/*");
        registration.setOrder(1);
        registration.setAsyncSupported(true);
        return registration;
    }
}
