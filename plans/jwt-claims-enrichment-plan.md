# Plan: JWT Claims Enrichment & User Identity Propagation

> **Date**: 2026-05-18  
> **Status**: Draft  
> **Scope**: identity-service, api-gateway, url-service, common

---

## 1. Industry Standard: How Microservices Propagate User Identity

There are three dominant patterns in production microservice architectures:

### Pattern A: Gateway Header Propagation (Recommended for this project)

```
Client → [Gateway: validates JWT, extracts claims, adds X-User-Id/X-User-Roles headers]
       → [Service: reads headers via filter → request attributes]
```

- **Used by**: Netflix Zuul, many Spring Cloud Gateway deployments
- **Pros**: Services don't need JWT libraries or public keys; simple; fast
- **Cons**: Must ensure internal endpoints aren't exposed externally (already done via `@Hidden` on `InternalUrlController`)
- **Security**: Headers are set by the gateway, not the client. Services trust the gateway on the internal network.

### Pattern B: JWT Passthrough (Zero-Trust)

```
Client → [Gateway: validates JWT, passes through]
       → [Service: validates JWT independently, extracts claims]
```

- **Used by**: High-security environments (financial, healthcare)
- **Pros**: Defense in depth; each service independently verifies
- **Cons**: Every service needs JWT decoder config, public key access; higher latency

### Pattern C: Hybrid (Gateway validates + passes enriched headers + JWT passthrough)

- Gateway validates, adds headers, AND forwards the JWT
- Services can use headers for quick access or validate JWT for sensitive ops

### Recommendation: **Pattern A** for this project

The architecture already follows this pattern — the gateway has `oauth2ResourceServer` JWT validation, and services use `@RequestAttribute("userId")`. We just need to complete the missing pieces.

---

## 2. Current State vs Target State

### Current JWT Claims
```json
{
  "sub": "john_doe",          // username (String)
  "tokenVersion": 0,
  "iat": 1716150000,
  "exp": 1716153600
}
```

### Target JWT Claims
```json
{
  "sub": "42",                // userId (String representation of Long)
  "userId": 42,               // userId as number (for convenience)
  "username": "john_doe",     // human-readable username
  "roles": ["ROLE_USER"],     // Spring Security authorities
  "tokenVersion": 0,
  "iat": 1716150000,
  "exp": 1716153600
}
```

### Current Identity Flow (BROKEN)
```
AuthController.signup/login
  → JwtService.generateToken(UserPrincipal, tokenVersion)
  → JWT: { sub: "john_doe", tokenVersion: 0 }
  → Client stores JWT

Client request with Authorization: Bearer <jwt>
  → Gateway: validates JWT signature + expiry + tokenVersion
  → Gateway: forwards request as-is (NO userId header added!)
  → UrlController: @RequestAttribute("userId") → NULL ❌
```

### Target Identity Flow
```
AuthController.signup/login
  → JwtService.generateToken(UserPrincipal, tokenVersion)
  → JWT: { sub: "42", userId: 42, username: "john_doe", roles: ["ROLE_USER"], tokenVersion: 0 }
  → Client stores JWT

Client request with Authorization: Bearer <jwt>
  → Gateway: validates JWT signature + expiry + tokenVersion
  → Gateway UserContextFilter: extracts userId, username, roles from Jwt object
  → Gateway: adds X-User-Id, X-Username, X-User-Roles headers
  → UrlController: @RequestAttribute("userId") → 42 ✅
```

---

## 3. Implementation Plan

### Phase 1: Enrich JWT Claims in identity-service (P0)

**Files to change:**

#### 1a. [`JwtService.java`](identity-service/src/main/java/com/miniurl/identity/service/JwtService.java)

Add `userId`, `username`, and `roles` to JWT claims. The `UserPrincipal` already has access to `user.getId()` and `user.getRole()`.

```java
public String generateToken(UserDetails userDetails, int tokenVersion) {
    if (!(userDetails instanceof UserPrincipal principal)) {
        throw new IllegalArgumentException("UserDetails must be UserPrincipal");
    }
    User user = principal.getUser();

    Map<String, Object> claims = new HashMap<>();
    claims.put("userId", user.getId());
    claims.put("username", user.getUsername());
    claims.put("roles", userDetails.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .collect(Collectors.toList()));
    claims.put("tokenVersion", tokenVersion);

    // Use userId as the subject (standard practice)
    return createToken(claims, user.getId().toString());
}
```

**Note**: Need to expose `getUser()` on `UserPrincipal` (currently package-private field).

#### 1b. [`UserPrincipal.java`](identity-service/src/main/java/com/miniurl/identity/entity/UserPrincipal.java)

Add a public getter:
```java
public User getUser() {
    return user;
}

public Long getUserId() {
    return user.getId();
}
```

#### 1c. [`TokenVersionValidator.java`](api-gateway/src/main/java/com/miniurl/gateway/security/TokenVersionValidator.java)

Currently uses `jwt.getSubject()` (which will change from username to userId). Update to use the `userId` claim instead:

```java
// Before:
String subject = jwt.getSubject();
String redisKey = TOKEN_VERSION_KEY_PREFIX + subject;

// After:
Long userId = jwt.getClaim("userId");
String redisKey = TOKEN_VERSION_KEY_PREFIX + userId;
```

**Backward compatibility**: During rollout, check both old (`sub` as username) and new (`userId` claim) formats.

#### 1d. [`RateLimiterConfig.java`](api-gateway/src/main/java/com/miniurl/gateway/config/RateLimiterConfig.java)

Update to use `userId` claim for rate limiting key:
```java
// Before:
return jwt.getSubject();

// After:
Long userId = jwt.getClaim("userId");
return userId != null ? userId.toString() : jwt.getSubject();
```

#### 1e. All identity-service controllers that manually parse JWT

These controllers extract username from JWT and look up user:
- [`ProfileController.java`](identity-service/src/main/java/com/miniurl/identity/controller/ProfileController.java) (lines 56-66, 94-102)
- [`SettingsController.java`](identity-service/src/main/java/com/miniurl/identity/controller/SettingsController.java) (lines 58-68, 107-115)
- [`FeatureFlagPublicController.java`](identity-service/src/main/java/com/miniurl/identity/controller/FeatureFlagPublicController.java) (lines 58-68)
- [`AuthController.java`](identity-service/src/main/java/com/miniurl/identity/controller/AuthController.java) (lines 265-273)

These should be updated to extract `userId` from the JWT claim instead of username lookup:

```java
// Before:
String username = jwtService.extractUsername(token);
User user = userRepository.findByUsername(username)
    .orElseThrow(() -> new ResourceNotFoundException("User not found"));

// After:
Long userId = jwtService.extractUserId(token);
User user = userRepository.findById(userId)
    .orElseThrow(() -> new ResourceNotFoundException("User not found"));
```

Add `extractUserId()` to [`JwtService`](identity-service/src/main/java/com/miniurl/identity/service/JwtService.java):
```java
public Long extractUserId(String token) {
    Claims claims = Jwts.parser()
            .verifyWith(keyService.getPublicKey())
            .build()
            .parseSignedClaims(token)
            .getPayload();
    return claims.get("userId", Long.class);
}
```

### Phase 2: Add UserContextFilter to API Gateway (P0)

**New file**: `api-gateway/src/main/java/com/miniurl/gateway/filter/UserContextFilter.java`

This `GlobalFilter` runs AFTER Spring Security authentication (order > 0), extracts claims from the authenticated `Jwt`, and adds headers for downstream services.

```java
@Component
public class UserContextFilter implements GlobalFilter, Ordered {

    private static final String HEADER_USER_ID = "X-User-Id";
    private static final String HEADER_USERNAME = "X-Username";
    private static final String HEADER_USER_ROLES = "X-User-Roles";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
            .map(ctx -> ctx.getAuthentication())
            .flatMap(auth -> {
                if (auth instanceof JwtAuthenticationToken jwtAuth) {
                    Jwt jwt = jwtAuth.getToken();
                    
                    Long userId = jwt.getClaim("userId");
                    String username = jwt.getClaim("username");
                    List<String> roles = jwt.getClaim("roles");

                    ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                        .header(HEADER_USER_ID, userId != null ? userId.toString() : "")
                        .header(HEADER_USERNAME, username != null ? username : "")
                        .header(HEADER_USER_ROLES, roles != null ? String.join(",", roles) : "")
                        .build();

                    return chain.filter(exchange.mutate().request(mutatedRequest).build());
                }
                return chain.filter(exchange);
            })
            .switchIfEmpty(chain.filter(exchange));
    }

    @Override
    public int getOrder() {
        // Run AFTER Spring Security authentication (which is at 0)
        return 1;
    }
}
```

### Phase 3: Add UserContextFilter to url-service (P0)

**New file**: `url-service/src/main/java/com/miniurl/url/config/UserContextFilter.java`

Reads the `X-User-Id` header set by the gateway and populates `request.setAttribute("userId", ...)`.

```java
@Component
public class UserContextFilter extends OncePerRequestFilter {

    private static final String HEADER_USER_ID = "X-User-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain)
            throws ServletException, IOException {
        String userIdHeader = request.getHeader(HEADER_USER_ID);
        if (userIdHeader != null && !userIdHeader.isBlank()) {
            request.setAttribute("userId", Long.valueOf(userIdHeader));
        }
        filterChain.doFilter(request, response);
    }
}
```

### Phase 4: Add UserContextFilter to feature-service (P1)

Same pattern as url-service — reads `X-User-Id` and `X-User-Roles` headers.

**New file**: `feature-service/src/main/java/com/miniurl/feature/config/UserContextFilter.java`

### Phase 5: Backward Compatibility & Rollout Strategy (P1)

Since existing valid JWTs have the old format (`sub` = username, no `userId` claim), we need a transition period:

1. **identity-service**: Generate new JWTs with enriched claims immediately
2. **Gateway `UserContextFilter`**: If `userId` claim is missing (old token), fall back to extracting username from `sub` and looking up userId (or skip — old tokens expire within 1 hour)
3. **Gateway `TokenVersionValidator`**: Support both old (`sub`=username) and new (`userId` claim) Redis key formats
4. **Remove fallback code** after `jwt.expiration-ms` (default 1 hour) has passed

---

## 4. Files Summary

| Action | File | Phase |
|--------|------|-------|
| MODIFY | `identity-service/src/main/java/com/miniurl/identity/service/JwtService.java` | P0 |
| MODIFY | `identity-service/src/main/java/com/miniurl/identity/entity/UserPrincipal.java` | P0 |
| MODIFY | `api-gateway/src/main/java/com/miniurl/gateway/security/TokenVersionValidator.java` | P0 |
| MODIFY | `api-gateway/src/main/java/com/miniurl/gateway/config/RateLimiterConfig.java` | P0 |
| MODIFY | `identity-service/src/main/java/com/miniurl/identity/controller/ProfileController.java` | P0 |
| MODIFY | `identity-service/src/main/java/com/miniurl/identity/controller/SettingsController.java` | P0 |
| MODIFY | `identity-service/src/main/java/com/miniurl/identity/controller/FeatureFlagPublicController.java` | P0 |
| MODIFY | `identity-service/src/main/java/com/miniurl/identity/controller/AuthController.java` | P0 |
| CREATE | `api-gateway/src/main/java/com/miniurl/gateway/filter/UserContextFilter.java` | P0 |
| CREATE | `url-service/src/main/java/com/miniurl/url/config/UserContextFilter.java` | P0 |
| CREATE | `feature-service/src/main/java/com/miniurl/feature/config/UserContextFilter.java` | P1 |

---

## 5. Verification Checklist

- [ ] New JWTs contain `userId`, `username`, `roles`, `tokenVersion` claims
- [ ] `sub` claim is the string representation of userId
- [ ] Gateway `UserContextFilter` adds `X-User-Id`, `X-Username`, `X-User-Roles` headers
- [ ] url-service `UserContextFilter` sets `request.setAttribute("userId", ...)`
- [ ] `UrlController.getUserUrls()` returns only the authenticated user's URLs
- [ ] `UrlController.getUrlById()` with another user's ID returns 404
- [ ] `UrlController.deleteUrl()` with another user's ID returns 404
- [ ] Old tokens (without `userId` claim) still work during transition period
- [ ] `TokenVersionValidator` works with new `userId`-based Redis keys
- [ ] Rate limiter uses `userId` for per-user rate limiting
- [ ] `mvn clean install` passes
