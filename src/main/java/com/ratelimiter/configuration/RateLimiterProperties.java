package com.ratelimiter.configuration;

import com.ratelimiter.domain.RateLimitAlgorithm;
import com.ratelimiter.domain.RedisClientType;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code ratelimiter.*} properties.
 *
 * <pre>
 * ratelimiter:
 *   default-limit: 100
 *   default-window-seconds: 60
 *   algorithm: SLIDING_WINDOW
 *   client: LETTUCE
 *   retry-after-seconds: 30
 *   jwt-secret: (hmac-sha256 key for resolving client id from JWT sub)
 *   admin-users: admin:admin123   # basic-auth users for POST/DELETE /ratelimit/limit
 * </pre>
 */
@ConfigurationProperties(prefix = "ratelimiter")
public class RateLimiterProperties {

    private int defaultLimit = 100;
    private int defaultWindowSeconds = 60;
    private RateLimitAlgorithm algorithm = RateLimitAlgorithm.SLIDING_WINDOW;
    private RedisClientType client = RedisClientType.LETTUCE;
    private int retryAfterSeconds = 30;
    private String jwtSecret = "change-me-in-production";
    /** Map of username -> password for admin basic-auth. */
    private java.util.LinkedHashMap<String, String> adminUsers =
            new java.util.LinkedHashMap<>(java.util.Map.of("admin", "admin123"));

    public int getDefaultLimit() { return defaultLimit; }
    public void setDefaultLimit(int defaultLimit) { this.defaultLimit = defaultLimit; }

    public int getDefaultWindowSeconds() { return defaultWindowSeconds; }
    public void setDefaultWindowSeconds(int defaultWindowSeconds) { this.defaultWindowSeconds = defaultWindowSeconds; }

    public RateLimitAlgorithm getAlgorithm() { return algorithm; }
    public void setAlgorithm(RateLimitAlgorithm algorithm) { this.algorithm = algorithm; }

    public RedisClientType getClient() { return client; }
    public void setClient(RedisClientType client) { this.client = client; }

    public int getRetryAfterSeconds() { return retryAfterSeconds; }
    public void setRetryAfterSeconds(int retryAfterSeconds) { this.retryAfterSeconds = retryAfterSeconds; }

    public String getJwtSecret() { return jwtSecret; }
    public void setJwtSecret(String jwtSecret) { this.jwtSecret = jwtSecret; }

    public java.util.LinkedHashMap<String, String> getAdminUsers() { return adminUsers; }
    public void setAdminUsers(java.util.LinkedHashMap<String, String> adminUsers) { this.adminUsers = adminUsers; }

    /** Nested Jedis pool config. */
    private Jedis jedis = new Jedis();

    public Jedis getJedis() { return jedis; }
    public void setJedis(Jedis jedis) { this.jedis = jedis; }

    public static class Jedis {
        private int maxTotal = 50;
        private int maxIdle = 10;
        private int minIdle = 5;
        private boolean blockIfExhausted = true;

        public int getMaxTotal() { return maxTotal; }
        public void setMaxTotal(int maxTotal) { this.maxTotal = maxTotal; }

        public int getMaxIdle() { return maxIdle; }
        public void setMaxIdle(int maxIdle) { this.maxIdle = maxIdle; }

        public int getMinIdle() { return minIdle; }
        public void setMinIdle(int minIdle) { this.minIdle = minIdle; }

        public boolean isBlockIfExhausted() { return blockIfExhausted; }
        public void setBlockIfExhausted(boolean blockIfExhausted) { this.blockIfExhausted = blockIfExhausted; }
    }
}
