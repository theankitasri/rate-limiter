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
 * Tests for {@link SlidingWindowRateLimiter} against a real (embedded) Redis.
 *
 * <p>Key scenario: a burst of {@code limit - 1} requests just before the window
 * edge, then another {@code limit - 1} just after — a fixed-window limiter
 * would allow both bursts (total = 2*limit - 2). A sliding-window limiter
 * must reject enough of the second burst to keep the rolling count at
 * {@code limit}.
 */
class SlidingWindowRateLimiterTest {

    @RegisterExtension
    static EmbeddedRedisExtension embeddedRedis = new EmbeddedRedisExtension();

    private static SlidingWindowRateLimiter limiter;

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
        limiter = new SlidingWindowRateLimiter(
                new RedisAdapters(template, factory, Executors.newSingleThreadExecutor(), 30),
                100, 60);
    }

    @Test
    void slidingWindowEnforcesRollingLimitNoBurstAtEdge() throws Exception {
        int limit = 10;
        int windowSeconds = 2;
        limiter.reset("edge-test").join();

        // Use a short window so we can observe rolling behaviour quickly.
        // Burst 1: 9 requests (limit - 1) — all allowed
        for (int i = 0; i < limit - 1; i++) {
            RateLimitDecision d = limiter.allowRequest("edge-test", limit, windowSeconds).join();
            assertTrue(d.allowed(), "Burst-1 request " + i + " should be allowed");
        }

        // Wait until the first burst is mostly outside the window
        Thread.sleep((windowSeconds * 1000L) / 2 + 500);

        // Burst 2: 9 more requests.
        // Rolling window = 2s. At this point, the first burst is ~1s old.
        // The window still contains all 9 first-burst requests, so the rolling
        // count should already be at limit - 1 = 9, meaning at most 1 more
        // request from burst 2 should be allowed.
        int allowedInBurst2 = 0;
        for (int i = 0; i < limit - 1; i++) {
            RateLimitDecision d = limiter.allowRequest("edge-test", limit, windowSeconds).join();
            if (d.allowed()) allowedInBurst2++;
        }
        assertTrue(allowedInBurst2 <= 1,
                "Rolling limit should allow at most 1 of burst-2, got " + allowedInBurst2);
    }

    @Test
    void concurrentRequestsNeverExceedLimit() throws Exception {
        int limit = 10;
        int threads = 50;
        limiter.reset("concurrent-sliding-test").join();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowedCount = new AtomicInteger();
        AtomicInteger rejectedCount = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    RateLimitDecision d =
                            limiter.allowRequest("concurrent-sliding-test", limit, 60).join();
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

        assertEquals(limit, allowedCount.get(),
                "Exactly " + limit + " requests should be allowed");
        assertEquals(threads - limit, rejectedCount.get());
    }
}
