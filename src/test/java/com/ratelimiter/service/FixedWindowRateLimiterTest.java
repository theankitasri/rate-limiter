package com.ratelimiter.service;

import com.ratelimiter.model.RateLimitDecision;
import com.ratelimiter.support.EmbeddedRedisExtension;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FixedWindowRateLimiter} against a real (embedded) Redis.
 */
class FixedWindowRateLimiterTest {

    @RegisterExtension
    static EmbeddedRedisExtension embeddedRedis = new EmbeddedRedisExtension();

    private static FixedWindowRateLimiter limiter;

    @BeforeAll
    static void setUp() {
        LettuceConnectionFactory factory =
                new LettuceConnectionFactory(new RedisStandaloneConfiguration("localhost", 63799));
        factory.afterPropertiesSet();
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.afterPropertiesSet();
        limiter = new FixedWindowRateLimiter(
                new RedisAdapters(template, factory, Executors.newSingleThreadExecutor(), 30),
                100, 60);
    }

    @Test
    void fixedWindowResetsAtBoundary() throws Exception {
        // limit=3, window=2s: 3 requests allowed, 4th rejected
        for (int i = 0; i < 3; i++) {
            RateLimitDecision d = limiter.allowRequest("boundary-test", 3, 2).join();
            assertTrue(d.allowed(), "Request " + i + " should be allowed in fresh window");
        }
        RateLimitDecision rejected = limiter.allowRequest("boundary-test", 3, 2).join();
        assertFalse(rejected.allowed(), "4th request should be rejected");
        assertTrue(rejected.retryAfterSeconds() > 0);
    }

    @Test
    void concurrentRequestsNeverExceedLimit() throws Exception {
        int limit = 10;
        int threads = 50;
        limiter.reset("concurrent-test").join();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowedCount = new AtomicInteger();
        AtomicInteger rejectedCount = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    RateLimitDecision d = limiter.allowRequest("concurrent-test", limit, 60).join();
                    if (d.allowed()) allowedCount.incrementAndGet();
                    else rejectedCount.incrementAndGet();
                } catch (Exception ex) {
                    fail("Unexpected exception: " + ex);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        assertEquals(limit, allowedCount.get(), "Exactly " + limit + " requests should be allowed");
        assertEquals(threads - limit, rejectedCount.get());
    }
}
