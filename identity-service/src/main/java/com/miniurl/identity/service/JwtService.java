package com.miniurl.identity.service;

import com.miniurl.identity.entity.UserPrincipal;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import java.security.PrivateKey;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for generating RS256 signed JWTs.
 * Only the Identity Service should use this as it requires the private key.
 *
 * JWT claims structure (Pattern B — Zero-Trust JWT Passthrough):
 *   sub:        username (backward compatible; services should prefer "userId" claim)
 *   userId:     database user ID (Long)
 *   username:   login username (String)
 *   roles:      list of role names, e.g. ["ROLE_USER", "ROLE_ADMIN"]
 *   tokenVersion: for token revocation support
 */
@Service
public class JwtService {

    private final KeyService keyService;
    private final Long expirationMs;

    public JwtService(KeyService keyService,
                      @Value("${jwt.expiration-ms:3600000}") Long expirationMs) {
        this.keyService = keyService;
        this.expirationMs = expirationMs;
    }

    /**
     * Generates a JWT with enriched claims (userId, username, roles).
     * Extracts claims from UserPrincipal if available; falls back to basic claims otherwise.
     */
    public String generateToken(UserDetails userDetails) {
        Map<String, Object> claims = buildClaims(userDetails);
        return createToken(claims, userDetails.getUsername());
    }

    /**
     * Generates a JWT with enriched claims including tokenVersion for revocation support.
     */
    public String generateToken(UserDetails userDetails, int tokenVersion) {
        Map<String, Object> claims = buildClaims(userDetails);
        claims.put("tokenVersion", tokenVersion);
        return createToken(claims, userDetails.getUsername());
    }

    /**
     * Builds enriched claims from UserDetails.
     * If UserDetails is a UserPrincipal, extracts userId, username, and roles.
     */
    private Map<String, Object> buildClaims(UserDetails userDetails) {
        Map<String, Object> claims = new HashMap<>();

        if (userDetails instanceof UserPrincipal principal) {
            claims.put("userId", principal.getUserId());
            claims.put("username", principal.getUsername());
            List<String> roles = principal.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .toList();
            claims.put("roles", roles);
        }

        return claims;
    }

    private String createToken(Map<String, Object> claims, String subject) {
        Date now = new Date();
        Date expirationDate = new Date(now.getTime() + expirationMs);
        PrivateKey privateKey = keyService.getPrivateKey();

        return Jwts.builder()
                .claims(claims)
                .subject(subject)
                .issuedAt(now)
                .expiration(expirationDate)
                .signWith(privateKey)
                .compact();
    }

    /**
     * Extracts the username (subject) from a JWT token.
     * Used for operations that must identify the caller from their token,
     * such as delete-account (prevents userId spoofing from request body).
     *
     * @deprecated Prefer {@link #extractUserId(String)} for new code.
     *             Kept for backward compatibility during rollout.
     */
    @Deprecated
    public String extractUsername(String token) {
        return parseClaims(token).getSubject();
    }

    /**
     * Extracts the userId claim from a JWT token.
     * This is the preferred method for identifying the caller in Pattern B (Zero-Trust).
     */
    public Long extractUserId(String token) {
        Object userId = parseClaims(token).get("userId");
        if (userId instanceof Integer i) {
            return i.longValue();
        }
        return (Long) userId;
    }

    /**
     * Extracts the roles claim from a JWT token.
     */
    @SuppressWarnings("unchecked")
    public List<String> extractRoles(String token) {
        return (List<String>) parseClaims(token).get("roles");
    }

    /**
     * Parses and validates a JWT token, returning its claims.
     */
    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(keyService.getPublicKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
