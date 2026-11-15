package com.ratelimiter.configuration;

import com.ratelimiter.service.RedisAdapters;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.concurrent.Executor;

/**
 * Chooses the Redis client implementation at startup via
 * {@code ratelimiter.client} (LETTUCE default, or JEDIS).
 *
 * <p>Both paths expose a single {@link RedisTemplate} to the limiter
 * implementations, so algorithm code is client-agnostic.
 */
@Configuration
public class RedisConfiguration {

    /** Dedicated thread pool so blocking Redis I/O never occupies servlet threads. */
    @Bean
    public Executor redisAdaptersExecutor() {
        int poolSize = Math.max(4, Runtime.getRuntime().availableProcessors());
        return java.util.concurrent.Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "redis-adapter");
            t.setDaemon(true);
            return t;
        });
    }

    @Bean
    public RedisAdapters redisAdapters(RedisTemplate<String, Object> redisTemplate,
                                       RedisConnectionFactory connectionFactory,
                                       Executor redisAdaptersExecutor,
                                       RateLimiterProperties properties) {
        return new RedisAdapters(redisTemplate, connectionFactory, redisAdaptersExecutor,
                properties.getRetryAfterSeconds());
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }

    /**
     * Build a JedisPool only when {@code ratelimiter.client=JEDIS}; otherwise
     * the bean is not created (Lettuce is used via spring-data-redis
     * auto-configuration).
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "ratelimiter.client", havingValue = "JEDIS")
    public JedisPool jedisPool(RateLimiterProperties properties,
                               @org.springframework.beans.factory.annotation.Value(
                                       "${spring.data.redis.host:localhost}") String host,
                               @org.springframework.beans.factory.annotation.Value(
                                       "${spring.data.redis.port:6379}") int port) {
        GenericObjectPoolConfig<Jedis> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(properties.getJedis().getMaxTotal());
        poolConfig.setMaxIdle(properties.getJedis().getMaxIdle());
        poolConfig.setMinIdle(properties.getJedis().getMinIdle());
        poolConfig.setBlockWhenExhausted(properties.getJedis().isBlockIfExhausted());
        return new JedisPool(poolConfig, host, port);
    }
}
