package com.miniurl.identity;

import com.miniurl.identity.entity.Role;
import com.miniurl.identity.entity.User;
import com.miniurl.identity.entity.UserPrincipal;
import com.miniurl.identity.service.JwtService;
import com.miniurl.identity.service.KeyService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.core.userdetails.UserDetails;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("JwtService Tests")
class JwtServiceTest {

    private JwtService jwtService;
    private KeyService keyService;
    private java.security.PublicKey publicKey;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        String privPath = tempDir.resolve("private.pem").toString();
        String pubPath = tempDir.resolve("public.pem").toString();
        keyService = new KeyService(privPath, pubPath, "test-key-id");
        keyService.init();
        jwtService = new JwtService(keyService, 3600000L);
        publicKey = keyService.getPublicKey();
    }

    private UserPrincipal createTestPrincipal() {
        User user = User.builder()
                .id(42L)
                .username("testuser")
                .password("pass")
                .firstName("Test")
                .lastName("User")
                .email("test@example.com")
                .role(Role.builder().id(1L).name("USER").build())
                .build();
        return new UserPrincipal(user);
    }

    @Test
    @DisplayName("generateToken creates valid RS256-signed token with enriched claims")
    void generateToken() {
        UserPrincipal principal = createTestPrincipal();
        String token = jwtService.generateToken(principal);

        Claims claims = Jwts.parser()
                .verifyWith((java.security.interfaces.RSAPublicKey) publicKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        assertEquals("testuser", claims.getSubject());
        assertEquals(42, claims.get("userId", Integer.class));
        assertEquals("testuser", claims.get("username", String.class));
        assertEquals(List.of("ROLE_USER"), claims.get("roles", List.class));
        assertNotNull(claims.getIssuedAt());
        assertNotNull(claims.getExpiration());
    }

    @Test
    @DisplayName("generateToken with version claim includes all enriched claims")
    void generateTokenWithVersion() {
        UserPrincipal principal = createTestPrincipal();
        String token = jwtService.generateToken(principal, 2);

        Claims claims = Jwts.parser()
                .verifyWith((java.security.interfaces.RSAPublicKey) publicKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        assertEquals("testuser", claims.getSubject());
        assertEquals(42, claims.get("userId", Integer.class));
        assertEquals("testuser", claims.get("username", String.class));
        assertEquals(List.of("ROLE_USER"), claims.get("roles", List.class));
        assertEquals(2, claims.get("tokenVersion", Integer.class));
    }

    @Test
    @DisplayName("token signed by one key is rejected by a different key")
    void tokenFromDifferentKeyIsRejected(@TempDir Path tempDir) {
        String otherPrivPath = tempDir.resolve("other-private.pem").toString();
        String otherPubPath = tempDir.resolve("other-public.pem").toString();
        KeyService otherKeyService = new KeyService(otherPrivPath, otherPubPath, "other-key-id");
        otherKeyService.init();
        JwtService otherJwtService = new JwtService(otherKeyService, 3600000L);

        UserPrincipal principal = createTestPrincipal();
        String token = otherJwtService.generateToken(principal);

        assertThrows(Exception.class, () -> {
            Jwts.parser()
                    .verifyWith((java.security.interfaces.RSAPublicKey) publicKey)
                    .build()
                    .parseSignedClaims(token);
        }, "Token signed by a different key should be rejected");
    }

    @Test
    @DisplayName("token expiration is set correctly")
    void tokenExpiration() {
        UserPrincipal principal = createTestPrincipal();
        String token = jwtService.generateToken(principal);

        Claims claims = Jwts.parser()
                .verifyWith((java.security.interfaces.RSAPublicKey) publicKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        long ttl = claims.getExpiration().getTime() - claims.getIssuedAt().getTime();
        assertEquals(3600000L, ttl, 1000, "Token TTL should be approximately 1 hour");
    }

    @Test
    @DisplayName("extractUsername returns the subject from a valid token")
    void extractUsernameReturnsSubject() {
        UserPrincipal principal = createTestPrincipal();
        String token = jwtService.generateToken(principal);

        String username = jwtService.extractUsername(token);
        assertEquals("testuser", username);
    }

    @Test
    @DisplayName("extractUserId returns the userId claim from a valid token")
    void extractUserIdReturnsClaim() {
        UserPrincipal principal = createTestPrincipal();
        String token = jwtService.generateToken(principal);

        Long userId = jwtService.extractUserId(token);
        assertEquals(42L, userId);
    }

    @Test
    @DisplayName("extractRoles returns the roles claim from a valid token")
    void extractRolesReturnsClaim() {
        UserPrincipal principal = createTestPrincipal();
        String token = jwtService.generateToken(principal);

        List<String> roles = jwtService.extractRoles(token);
        assertEquals(List.of("ROLE_USER"), roles);
    }

    @Test
    @DisplayName("generateToken with plain UserDetails (not UserPrincipal) still works")
    void generateTokenWithPlainUserDetails() {
        UserDetails userDetails = org.springframework.security.core.userdetails.User
                .withUsername("plainuser").password("pass").roles("USER").build();
        String token = jwtService.generateToken(userDetails);

        Claims claims = Jwts.parser()
                .verifyWith((java.security.interfaces.RSAPublicKey) publicKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        assertEquals("plainuser", claims.getSubject());
        // Plain UserDetails won't have userId/username/roles claims
        assertNull(claims.get("userId"));
    }
}
