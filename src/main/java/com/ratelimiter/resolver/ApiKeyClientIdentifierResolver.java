package com.ratelimiter.resolver;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Extracts the client identifier from the {@code X-Api-Key} header.
 */
@Component
public class ApiKeyClientIdentifierResolver implements ClientIdentifierResolver {

    @Override
    public String resolve(HttpServletRequest request) {
        return request.getHeader("X-Api-Key");
    }

    @Override
    public int order() {
        return 200;
    }
}
