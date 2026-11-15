package com.ratelimiter.support;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import redis.embedded.RedisServer;

/**
 * JUnit 5 extension that starts an embedded Redis server on port 63799
 * for every test class that uses it.
 */
public class EmbeddedRedisExtension implements BeforeAllCallback, AfterAllCallback {

    private static final int PORT = 63799;
    private static volatile RedisServer server;

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        if (server == null) {
            synchronized (EmbeddedRedisExtension.class) {
                if (server == null) {
                    server = RedisServer.builder()
                            .port(PORT)
                            .build();
                    server.start();
                }
            }
        }
    }

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        if (server != null) {
            server.stop();
            server = null;
        }
    }
}
