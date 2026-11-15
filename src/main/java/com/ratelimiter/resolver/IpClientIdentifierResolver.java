package com.ratelimiter.resolver;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Fallback: uses the client's IP address (honouring {@code X-Forwarded-For}).
 */
@Component
public class IpClientIdentifierResolver implements ClientIdentifierResolver {

    @Override
    public String resolve(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return "ip:" + xff.split(",")[0].trim();
        }
        return "ip:" + request.getRemoteAddr();
    }

    @Override
    public int order() {
        return 300;
    }
}
