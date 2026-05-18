package com.miniurl.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Rate limiter key resolution.
 *
 * Pattern B (Zero-Trust): Uses "userId" claim from enriched JWT for per-user
 * rate limiting. Falls back to "sub" for legacy tokens.
 */
@Configuration
public class RateLimiterConfig {

    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> exchange.getPrincipal()
                .map(principal -> {
                    if (principal instanceof org.springframework.security.oauth2.jwt.Jwt jwt) {
                        // Prefer userId claim (enriched JWT), fall back to sub (legacy)
                        Object userIdClaim = jwt.getClaims().get("userId");
                        if (userIdClaim != null) {
                            return String.valueOf(userIdClaim);
                        }
                        return jwt.getSubject();
                    }
                    return principal.getName();
                })
                .defaultIfEmpty("anonymous");
    }
}
