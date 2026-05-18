package com.miniurl.util;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@Component
public class JwtUtil {

    @Value("${app.jwt.secret}")
    private String secret;

    @Value("${app.jwt.expiration-ms}")
    private Long expirationMs;

    // Validate JWT secret is not the default
    @jakarta.annotation.PostConstruct
    public void validateJwtSecret() {   
        if (secret.length() < 32) {
            throw new IllegalStateException("SECURITY CRITICAL: JWT secret must be at least 32 characters long");
        }
    }

    private SecretKey getSigningKey() {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Check if token is expired
     * @param token the JWT token to check
     * @return true if token is expired, false otherwise
     */
    public Boolean isTokenExpired(String token) {
        try {
            return extractExpiration(token).before(new Date());
        } catch (Exception e) {
            // If we can't extract expiration, consider it expired
            return true;
        }
    }

    public String generateToken(UserDetails userDetails) {
        Map<String, Object> claims = new HashMap<>();
        return createToken(claims, userDetails.getUsername());
    }

    /**
     * Generate token with token version for invalidation support
     */
    public String generateToken(UserDetails userDetails, int tokenVersion) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("tokenVersion", tokenVersion);
        return createToken(claims, userDetails.getUsername());
    }

    private String createToken(Map<String, Object> claims, String subject) {
        Date now = new Date();
        Date expirationDate = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .claims(claims)
                .subject(subject)
                .issuedAt(now)
                .expiration(expirationDate)
                .signWith(getSigningKey())
                .compact();
    }

    public Boolean validateToken(String token, UserDetails userDetails) {
        final String username = extractUsername(token);
        if (!username.equals(userDetails.getUsername())) {
            return false;
        }
        if (isTokenExpired(token)) {
            return false;
        }
        // Validate token version if present
        try {
            Claims claims = extractAllClaims(token);
            if (claims.get("tokenVersion") != null && userDetails instanceof org.springframework.security.core.userdetails.User) {
                // Token version validation is done at service level
            }
        } catch (Exception e) {
            // Token version not present (old token format)
        }
        return true;
    }

    /**
     * Validate token with token version check
     */
    public Boolean validateToken(String token, UserDetails userDetails, int expectedTokenVersion) {
        final String username = extractUsername(token);
        if (!username.equals(userDetails.getUsername())) {
            return false;
        }
        if (isTokenExpired(token)) {
            return false;
        }
        // Check token version
        try {
            Claims claims = extractAllClaims(token);
            Integer tokenVersion = claims.get("tokenVersion", Integer.class);
            if (tokenVersion != null && tokenVersion != expectedTokenVersion) {
                return false; // Token has been invalidated
            }
        } catch (Exception e) {
            // Token version not present (old token format) - allow for backward compatibility
        }
        return true;
    }

    /**
     * Check if token needs renewal (expires within 5 minutes)
     */
    public boolean needsRenewal(String token) {
        try {
            Date expiration = extractExpiration(token);
            long now = System.currentTimeMillis();
            long renewalThreshold = 5 * 60 * 1000; // 5 minutes in milliseconds
            return (expiration.getTime() - now) < renewalThreshold;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Renew token if valid and user is active
     */
    public String renewToken(String token, UserDetails userDetails) {
        if (validateToken(token, userDetails)) {
            return generateToken(userDetails);
        }
        return null;
    }

    /**
     * Renew token with new token version
     */
    public String renewToken(String token, UserDetails userDetails, int tokenVersion) {
        if (validateToken(token, userDetails, tokenVersion)) {
            return generateToken(userDetails, tokenVersion);
        }
        return null;
    }

    /**
     * Check if token is expiring soon and return new token if renewal needed
     * Returns null if no renewal needed
     */
    public String renewTokenIfNeeded(String token, UserDetails userDetails) {
        if (needsRenewal(token)) {
            return renewToken(token, userDetails);
        }
        return null;
    }

    public Long getUserIdFromToken(String token) {
        Claims claims = extractAllClaims(token);
        return claims.get("userId", Long.class);
    }

    public Integer getTokenVersion(String token) {
        try {
            Claims claims = extractAllClaims(token);
            return claims.get("tokenVersion", Integer.class);
        } catch (Exception e) {
            return null;
        }
    }

    public Long getExpirationMs() {
        return expirationMs;
    }
}
