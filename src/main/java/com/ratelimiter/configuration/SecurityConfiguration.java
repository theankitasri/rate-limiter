package com.ratelimiter.configuration;

import io.jsonwebtoken.security.Keys;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * Security wiring:
 *
 * <ul>
 *   <li>Actuator: accessible from loopback without auth, or by any admin
 *       (basic-auth or JWT with {@code role=admin}).</li>
 *   <li>{@code POST/DELETE /ratelimit/limit*}: admin only.</li>
 *   <li>Everything else: permit all — the rate limiter filter itself is the
 *       security boundary for non-admin traffic.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

    private static final IpAddressMatcher LOOPBACK_V4 = new IpAddressMatcher("127.0.0.1");
    private static final IpAddressMatcher LOOPBACK_V6 = new IpAddressMatcher("::1");

    @Bean
    public SecretKey jwtSigningKey(RateLimiterProperties properties) {
        byte[] secret = properties.getJwtSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException(
                    "ratelimiter.jwt-secret must be at least 32 bytes (256 bits) for HS256");
        }
        return Keys.hmacShaKeyFor(secret);
    }

    @Bean
    public UserDetailsService adminUserDetailsService(RateLimiterProperties properties) {
        InMemoryUserDetailsManager manager = new InMemoryUserDetailsManager();
        properties.getAdminUsers().forEach((username, password) ->
                manager.createUser(User.withUsername(username)
                        .password("{noop}" + password)
                        .roles("ADMIN")
                        .build()));
        return manager;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                    JwtAuthFilter jwtAuthFilter) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .headers(h -> h.frameOptions(HeadersConfigurer.FrameOptionsConfig::disable))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/**").access(
                        (authentication, context) -> {
                            var req = (jakarta.servlet.http.HttpServletRequest) context.getRequest();
                            var principal = authentication.get();
                            boolean isLoopback = LOOPBACK_V4.matches(req) || LOOPBACK_V6.matches(req);
                            boolean isAdmin = principal.isAuthenticated()
                                    && principal.getAuthorities().stream()
                                            .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
                            return isLoopback || isAdmin
                                    ? new org.springframework.security.authorization.AuthorizationDecision(true)
                                    : new org.springframework.security.authorization.AuthorizationDecision(false);
                        })
                .requestMatchers("/admin", "/admin/**", "/css/**", "/js/**", "/index.html").permitAll()
                .requestMatchers(HttpMethod.POST, "/ratelimit/limit").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/ratelimit/limit/**").hasRole("ADMIN")
                .anyRequest().permitAll()
            )
            .httpBasic(basic -> basic
                .authenticationEntryPoint(
                        (req, res, ex) -> res.sendError(401, "Authentication required")));
        http.addFilterBefore(jwtAuthFilter,
                org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
