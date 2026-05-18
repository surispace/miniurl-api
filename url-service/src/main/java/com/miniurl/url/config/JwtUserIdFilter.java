package com.miniurl.url.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Extracts userId from the validated JWT and sets it as a request attribute.
 *
 * Pattern B (Zero-Trust): The JWT has already been validated by Spring Security's
 * OAuth2 Resource Server filter. This filter simply extracts the "userId" claim
 * and makes it available to controllers via {@code @RequestAttribute("userId")}.
 *
 * This replaces the gateway header propagation approach — each service independently
 * validates and extracts claims from the JWT.
 */
@Component
public class JwtUserIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtUserIdFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            Object userIdClaim = jwt.getClaims().get("userId");
            if (userIdClaim != null) {
                Long userId;
                if (userIdClaim instanceof Integer i) {
                    userId = i.longValue();
                } else {
                    userId = (Long) userIdClaim;
                }
                request.setAttribute("userId", userId);
                log.debug("Set userId={} from JWT for request {}", userId, request.getRequestURI());
            } else {
                log.warn("JWT missing 'userId' claim for request {}. Token may be from before claims enrichment.",
                        request.getRequestURI());
            }
        }

        filterChain.doFilter(request, response);
    }
}
