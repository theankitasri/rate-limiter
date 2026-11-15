package com.ratelimiter.resolver;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Strategy for extracting a stable client identifier from an incoming request.
 *
 * <p>Multiple implementations are evaluated in descending
 * {@link Order()}. The first non-null result wins. New sources can be added by
 * implementing this interface and registering a Spring bean.
 */
public interface ClientIdentifierResolver {

    /**
     * @return client id or {@code null} if this resolver cannot identify the
     *         client
     */
    String resolve(HttpServletRequest request);

    /**
     * Lower value = evaluated first. Default order:
     * JWT(100) → API key(200) → IP(300).
     */
    default int order() {
        return Integer.MAX_VALUE;
    }
}
