package com.miniurl.gateway.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Custom JWT validator that checks the tokenVersion claim against Redis.
 * Implements NIST SP 800-63B §5.2.5 and OWASP ASVS V3.2 — token invalidation
 * on security events (password change, account deactivation, etc.).
 *
 * When a user's password is changed or account is deactivated, the identity-service
 * increments the tokenVersion and stores it in Redis under "user:tokenVersion:{userId}".
 * This validator rejects any JWT whose tokenVersion claim is less than the stored value.
 *
 * Redis key resolution: Prefers "userId" claim (enriched JWT), falls back to "sub" (legacy JWT).
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
        Integer tokenVersion = jwt.getClaim("tokenVersion");
        String userIdentifier = resolveUserIdentifier(jwt);

        if (tokenVersion == null) {
            log.debug("JWT for '{}' has no tokenVersion claim — allowing", userIdentifier);
            return OAuth2TokenValidatorResult.success();
        }

        String redisKey = TOKEN_VERSION_KEY_PREFIX + userIdentifier;
        String storedVersionStr = redisTemplate.opsForValue().get(redisKey).block();

        if (storedVersionStr == null) {
            log.debug("No stored tokenVersion for '{}' — allowing", userIdentifier);
            return OAuth2TokenValidatorResult.success();
        }

        int storedVersion;
        try {
            storedVersion = Integer.parseInt(storedVersionStr);
        } catch (NumberFormatException e) {
            log.warn("Invalid tokenVersion in Redis for '{}': {}", userIdentifier, storedVersionStr);
            return OAuth2TokenValidatorResult.success();
        }

        if (tokenVersion < storedVersion) {
            log.warn("JWT rejected for '{}': tokenVersion {} < stored {}",
                    userIdentifier, tokenVersion, storedVersion);
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

    /**
     * Resolves the user identifier for the Redis key.
     * Prefers "userId" claim (enriched JWT), falls back to "sub" (legacy JWT).
     */
    private String resolveUserIdentifier(Jwt jwt) {
        Object userIdClaim = jwt.getClaims().get("userId");
        if (userIdClaim != null) {
            return String.valueOf(userIdClaim);
        }
        return jwt.getSubject();
    }
}
