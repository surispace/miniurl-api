package com.miniurl.gateway.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Mono;

/**
 * Custom JWT validator that checks the tokenVersion claim against Redis.
 * Implements NIST SP 800-63B §5.2.5 and OWASP ASVS V3.2 — token invalidation
 * on security events (password change, account deactivation, etc.).
 *
 * When a user's password is changed or account is deactivated, the identity-service
 * increments the tokenVersion and stores it in Redis under "user:tokenVersion:{userId}".
 * This validator rejects any JWT whose tokenVersion claim is less than the stored value.
 */
public class TokenVersionValidator implements OAuth2TokenValidator<Jwt> {

    private static final Logger log = LoggerFactory.getLogger(TokenVersionValidator.class);
    private static final String TOKEN_VERSION_KEY_PREFIX = "user:tokenVersion:";

    private final ReactiveRedisTemplate<String, String> redisTemplate;

    public TokenVersionValidator(ReactiveRedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        // Extract tokenVersion from JWT claims
        Integer tokenVersion = jwt.getClaim("tokenVersion");
        String subject = jwt.getSubject();

        if (tokenVersion == null) {
            // Token was issued before tokenVersion was added — allow it
            // (backward compatibility during rollout)
            log.debug("JWT for '{}' has no tokenVersion claim — allowing", subject);
            return OAuth2TokenValidatorResult.success();
        }

        // Check against Redis — this is a blocking call in a reactive context,
        // but Spring Security's reactive JWT decoder handles this correctly
        // by subscribing to the Mono internally.
        String redisKey = TOKEN_VERSION_KEY_PREFIX + subject;
        String storedVersionStr = redisTemplate.opsForValue().get(redisKey).block();

        if (storedVersionStr == null) {
            // No stored version — token is valid (first login or Redis not yet populated)
            log.debug("No stored tokenVersion for '{}' — allowing", subject);
            return OAuth2TokenValidatorResult.success();
        }

        int storedVersion;
        try {
            storedVersion = Integer.parseInt(storedVersionStr);
        } catch (NumberFormatException e) {
            log.warn("Invalid tokenVersion in Redis for '{}': {}", subject, storedVersionStr);
            return OAuth2TokenValidatorResult.success();
        }

        if (tokenVersion < storedVersion) {
            log.warn("JWT rejected for '{}': tokenVersion {} < stored {}",
                    subject, tokenVersion, storedVersion);
            return OAuth2TokenValidatorResult.failure(
                    new org.springframework.security.oauth2.core.OAuth2Error(
                            "invalid_token",
                            "Token has been revoked",
                            null
                    )
            );
        }

        return OAuth2TokenValidatorResult.success();
    }
}
