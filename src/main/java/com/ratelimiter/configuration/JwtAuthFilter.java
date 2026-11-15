package com.ratelimiter.configuration;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.util.List;

/**
 * Extracts authentication from the {@code Authorization: Bearer <jwt>} header
 * (if present) and populates the {@link SecurityContext} with a
 * {@code ROLE_ADMIN} authority when the claim {@code role == "admin"}.
 *
 * <p>Admin users configured via {@code ratelimiter.admin-users} are still
 * authenticated via HTTP basic (see {@code InMemoryUserDetailsRepository}).
 *
 * <p>This filter is stateless and runs before {@code SecurityFilterChain}
 * authorization decisions.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    private final RateLimiterProperties properties;
    private final SecretKey jwtSigningKey;

    public JwtAuthFilter(RateLimiterProperties properties, SecretKey jwtSigningKey) {
        this.properties = properties;
        this.jwtSigningKey = jwtSigningKey;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            try {
                String token = authHeader.substring(7);
                Claims claims = Jwts.parser()
                        .verifyWith(jwtSigningKey)
                        .build()
                        .parseSignedClaims(token)
                        .getPayload();
                String subject = claims.getSubject();
                String role = claims.get("role", String.class);
                if ("admin".equalsIgnoreCase(role)) {
                    var authority = new SimpleGrantedAuthority("ROLE_ADMIN");
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(
                                    subject, null, List.of(authority)));
                }
            } catch (Exception ex) {
                log.debug("Invalid JWT: {}", ex.getMessage());
            }
        }
        chain.doFilter(request, response);
    }
}
