package com.ratelimiter.domain;

/** Redis client implementation, selectable via {@code ratelimiter.client}. */
public enum RedisClientType {
    /** Default. Async-capable (CompletableFuture) via spring-data-redis. */
    LETTUCE,
    /** Blocking Jedis client; async wrappers run on the {@code RedisAdapters} executor. */
    JEDIS
}
