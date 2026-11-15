package com.ratelimiter.resolver;

import com.ratelimiter.configuration.RateLimiterProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;

/**
 * Extracts the client identifier from the {@code sub} claim of a Bearer JWT.
 */
@Component
public class JwtClientIdentifierResolver implements ClientIdentifierResolver {

    private static final Logger log = LoggerFactory.getLogger(JwtClientIdentifierResolver.class);
    private final RateLimiterProperties properties;
    private final SecretKey jwtSigningKey;

    public JwtClientIdentifierResolver(RateLimiterProperties properties,
                                       SecretKey jwtSigningKey) {
        this.properties = properties;
        this.jwtSigningKey = jwtSigningKey;
    }

    @Override
    public String resolve(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(jwtSigningKey)
                    .build()
                    .parseSignedClaims(authHeader.substring(7))
                    .getPayload();
            return claims.getSubject();
        } catch (Exception ex) {
            log.debug("JWT resolution failed: {}", ex.getMessage());
            return null;
        }
    }

    @Override
    public int order() {
        return 100;
    }
}
