package com.miniurl.gateway.config;

import com.miniurl.gateway.security.TokenVersionValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Gateway security configuration.
 *
 * Issue 3 fix: Custom JWT decoder with TokenVersionValidator that checks
 * the tokenVersion claim against Redis. This enables server-side token
 * revocation on password change, account deactivation, etc.
 * (NIST SP 800-63B §5.2.5, OWASP ASVS V3.2)
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}")
    private String jwkSetUri;

    private final ReactiveRedisTemplate<String, String> redisTemplate;

    public SecurityConfig(ReactiveRedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Bean
    public ReactiveJwtDecoder jwtDecoder() {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(new TokenVersionValidator(redisTemplate));
        return decoder;
    }

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http,
                                                             ReactiveJwtDecoder jwtDecoder) {
        http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .authorizeExchange(exchanges -> exchanges
                // Public endpoints
                .pathMatchers("/api/auth/**", "/api/features/public/**", "/api/health", "/r/{code}", "/actuator/**").permitAll()
                // Public endpoints - Swagger/OpenAPI
                .pathMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**", "/webjars/**").permitAll()
                // All other requests require authentication
                .anyExchange().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtDecoder(jwtDecoder))
            );

        return http.build();
    }
}
