# MiniURL Migration Audit: Monolith → Microservices

**Date:** 2026-04-30
**Auditor:** Automated Deep Analysis
**Scope:** Full comparison of `miniurl-monolith` (200+ tests) vs microservices implementation (~70 tests)
**Verdict Standard:** Treat missing tests as missing functionality until proven otherwise.

---

## 1. Executive Verdict

### **VERDICT: NO-GO — NOT READY FOR PRODUCTION MIGRATION**

The microservices implementation is approximately **40-50% complete** relative to the monolith. Critical security features, entire controller surfaces, resilience patterns, and comprehensive test coverage are absent. Deploying this in its current state would represent a **significant regression** in security posture, functional completeness, and operational reliability.

**Summary of Gaps:**

| Category | Monolith | Microservices | Gap Severity |
|---|---|---|---|
| API Endpoints | 30+ | ~15 | **P0** |
| Security Features | 8 | 3 | **P0** |
| Resilience Patterns | 4 | 0 | **P0** |
| Integration Tests | 8 test classes | 0 | **P0** |
| Unit Tests | 15+ classes | 5 classes | **P0** |
| Admin Functionality | Full CRUD | None | **P0** |
| Email Reliability | CircuitBreaker/Retry/Bulkhead | Fire-and-forget Kafka | **P1** |
| Rate Limiting | Dual-layer per-endpoint | Generic gateway-only | **P1** |
| Audit Logging | Full | Schema only, no code | **P1** |

---

## 2. Functional Parity Matrix

### 2.1 Authentication & Identity

| Feature | Monolith | Microservices | Status |
|---|---|---|---|
| POST /api/auth/signup | Returns success message, requires invitationToken | Returns JWT immediately | **REGRESSION** — changed behavior |
| GET /api/auth/verify-email-invite | Validates invite token, returns email | Same | MATCH |
| GET /api/auth/verify-email | Validates reset-password token | **Calls verifyEmailInviteToken instead of verifyResetPasswordToken** | **P0 BUG** |
| POST /api/auth/forgot-password | Anti-enumeration (same response), 20min cooldown per email | No anti-enumeration | **P0 GAP** |
| POST /api/auth/reset-password | Validates token, sets new password | Same | MATCH |
| POST /api/auth/login | Checks account lockout, checks 2FA flag, conditional OTP | **No lockout check, always sends OTP** | **P0 GAP** |
| POST /api/auth/verify-otp | Validates OTP, returns JWT | Same | MATCH |
| POST /api/auth/resend-otp | 30s cooldown, reuses same OTP | Same | MATCH |
| POST /api/auth/delete-account | Extracts username from JWT token | **Takes userId from request body** | **P0 GAP** — security regression |
| Account Lockout (5 fails → 5min) | Enforced in login flow | Entity has logic, **controller doesn't call it** | **P0 GAP** |
| Password Strength (NIST SP 800-63B) | Min 8 chars, common password check, username-in-password check | Same validation present | MATCH |
| Signup Rate Limiting (12min cooldown) | In-memory ConcurrentHashMap | Same | MATCH |
| Token Version Invalidation | Supported | Supported | MATCH |

### 2.2 URL Management

| Feature | Monolith | Microservices | Status |
|---|---|---|---|
| POST /api/urls | @Valid on request, returns ApiResponse | **Missing @Valid on CreateUrlRequest** | **P1 GAP** |
| GET /api/urls | Paginated with query params | Paginated via GET | MATCH |
| GET /api/urls/paged | N/A (uses GET /api/urls?page=&size=&sortBy=) | **POST /paged with @RequestBody** | **P1 GAP** — wrong HTTP method |
| GET /api/urls/{id} | Ownership check, returns ApiResponse | Ownership check, returns raw UrlResponse | **P2 GAP** — inconsistent response format |
| DELETE /api/urls/{id} | Ownership check, hard delete | Ownership check, hard delete + outbox event | MATCH |
| GET /api/urls/usage-stats | Returns usage statistics | Returns usage statistics | MATCH |
| Short Code Generation | Random 6-char alphanumeric, collision retry (max 100) | Snowflake ID + Base62 (deterministic) | **DESIGN CHANGE** — acceptable |
| URL Validation | SSRF protection, blocked domains, self-referencing check, protocol whitelist | Same validation present | MATCH |
| Alias Validation | 3-10 chars, alphanumeric only | Same | MATCH |
| URL Limit Tracking | url_usage_limits table + service | Table exists, **no service code** | **P1 GAP** |

### 2.3 Redirect

| Feature | Monolith | Microservices | Status |
|---|---|---|---|
| GET /r/{shortCode} | Synchronous DB lookup, increments accessCount, validates redirect URL | Redis-first cache, WebFlux reactive, Kafka click event | **ARCHITECTURAL CHANGE** |
| Redirect URL Validation | Blocks javascript:, data:, vbscript:, file: | **MISSING** | **P0 GAP** — open redirect vulnerability |
| Access Counting | Synchronous increment in findByShortCode | Async Kafka event → analytics service | **BEHAVIORAL CHANGE** — eventual consistency |
| Cache Strategy | None (direct DB) | Redis with 1hr TTL, cache-aside pattern | IMPROVEMENT |
| HTTP Status | 302 FOUND | 302 FOUND | MATCH |

### 2.4 Admin Functions

| Feature | Monolith | Microservices | Status |
|---|---|---|---|
| GET /api/admin/users | Paginated, searchable, filterable by status, sort validation | **MISSING** | **P0 GAP** |
| GET /api/admin/users/{id} | Returns user details | **MISSING** | **P0 GAP** |
| GET /api/admin/users/search | Search by username/email | **MISSING** | **P0 GAP** |
| POST /api/admin/users/{id}/deactivate | Soft-delete with audit log | **MISSING** | **P0 GAP** |
| POST /api/admin/users/{id}/activate | Reactivate with audit log | **MISSING** | **P0 GAP** |
| POST /api/admin/users/{id}/suspend | Suspend with audit log | **MISSING** | **P0 GAP** |
| POST /api/admin/users/{id}/role | Change role with audit log | **MISSING** | **P0 GAP** |
| GET /api/admin/stats | User/URL statistics | **MISSING** | **P0 GAP** |
| GET /api/admin/email-invites | Paginated with search | **MISSING** | **P0 GAP** |
| POST /api/admin/email-invites/send | Send invite email | **MISSING** | **P0 GAP** |
| POST /api/admin/email-invites/{id}/resend | Resend invite | **MISSING** | **P0 GAP** |
| POST /api/admin/email-invites/{id}/revoke | Revoke invite | **MISSING** | **P0 GAP** |
| GET /api/admin/features | List all feature flags | Partially in feature-service | **P1 GAP** |
| PUT /api/admin/features/{id}/toggle | Toggle feature flag | @PatchMapping in feature-service | **P2 GAP** |
| POST /api/admin/features | Create feature flag | Present | MATCH |
| DELETE /api/admin/features/{id} | Delete feature flag | Present | MATCH |
| GET /api/admin/features/global | List global flags | Present at /api/global-flags | **P1 GAP** — different path |
| PUT /api/admin/features/global/{id}/toggle | Toggle global flag | @PatchMapping at /api/global-flags/{id}/toggle | **P2 GAP** |

### 2.5 User Profile & Settings

| Feature | Monolith | Microservices | Status |
|---|---|---|---|
| GET /api/profile | Returns user profile with theme | **MISSING** | **P0 GAP** |
| PUT /api/profile | Partial updates, audit logging | **MISSING** | **P0 GAP** |
| GET /api/settings/export | Exports user data + URLs as JSON | **MISSING** | **P0 GAP** |
| POST /api/settings/delete-account | Password confirmation, soft delete | **MISSING** | **P0 GAP** |

### 2.6 Public Endpoints

| Feature | Monolith | Microservices | Status |
|---|---|---|---|
| POST /api/self-invite/send | Checks GLOBAL_USER_SIGNUP flag, validates email | **MISSING** | **P0 GAP** |
| GET /api/features/global | Returns global flags (no auth) | **MISSING** (feature-service has /api/global-flags but not routed as public) | **P0 GAP** |
| GET /api/features | Returns features for user's role | **MISSING** | **P0 GAP** |
| GET /api/health | Health check | **MISSING** in identity service | **P1 GAP** |

---

## 3. API Contract Compatibility

### 3.1 Response Format Inconsistency

**Monolith** wraps all responses in a standardized envelope:
```json
{
  "success": true/false,
  "message": "Human-readable message",
  "data": { ... }
}
```

**Microservices** are inconsistent:
- Identity service AuthController: Uses `ApiResponse` wrapper ✓
- URL service UrlController: Uses `ApiResponse` wrapper ✓
- Feature service FeatureController: **Returns raw DTOs** (no ApiResponse) ✗
- Feature service GlobalFlagController: **Returns raw DTOs** ✗
- Redirect service: Returns `ResponseEntity<Object>` ✗

### 3.2 HTTP Method Mismatches

| Endpoint | Monolith | Microservices | Issue |
|---|---|---|---|
| Feature toggle | PUT /api/admin/features/{id}/toggle | PATCH /api/features/{id}/toggle | Method mismatch |
| Global flag toggle | PUT /api/admin/features/global/{id}/toggle | PATCH /api/global-flags/{id}/toggle | Method mismatch |
| URL paginated list | GET /api/urls?page=&size=&sortBy= | POST /api/urls/paged (body) | Wrong HTTP method |

### 3.3 Path Changes

| Monolith Path | Microservices Path | Impact |
|---|---|---|
| /api/admin/features/** | /api/features/** | Admin prefix dropped |
| /api/admin/features/global/** | /api/global-flags/** | Different base path |
| /api/features/global (public) | Not routed publicly | Breaking change for frontend |

### 3.4 Request Body Differences

| Endpoint | Monolith | Microservices |
|---|---|---|
| Signup | Returns `{success, message}` | Returns `{success, message, data: {token, username, userId}}` |
| Login (2FA disabled) | Returns JWT in data.token | Always returns OTP pending |
| Delete Account | No body (extracts from JWT) | Requires `{userId}` in body |

---

## 4. Data Model & Migration Analysis

### 4.1 Schema Comparison

**Monolith: Single `miniurldb` database**

| Table | Monolith | Identity DB | URL DB | Feature DB |
|---|---|---|---|---|
| roles | ✓ | ✓ | — | — |
| users | ✓ (with FK to roles) | ✓ (with FK to roles) | — | — |
| urls | ✓ (FK to users) | — | ✓ (no FK, just user_id BIGINT) | — |
| verification_tokens | ✓ (FK to users) | ✓ (no FK constraint!) | — | — |
| email_invites | ✓ | ✓ | — | — |
| audit_logs | ✓ (FK to users) | ✓ (no FK constraint!) | — | — |
| url_usage_limits | ✓ (FK to users) | — | ✓ (no FK constraint!) | — |
| features | ✓ | — | — | ✓ |
| feature_flags | ✓ (FK to features, roles) | — | — | ✓ (FK to features only) |
| global_flags | ✓ (FK to features) | — | — | ✓ (FK to features) |
| outbox | — | ✓ | ✓ | — |

### 4.2 Critical Data Model Issues

1. **Lost Referential Integrity:** `urls.user_id` in URL DB is a bare `BIGINT` with no FK constraint to identity DB. Data consistency relies entirely on application-level enforcement.

2. **verification_tokens Table:** Identity DB schema **omits the FK constraint** to users that exists in the monolith (`CONSTRAINT fk_verification_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE`).

3. **audit_logs Table:** Identity DB schema **omits the FK constraint** to users. More critically, **no service code writes to this table** — it's dead schema.

4. **url_usage_limits Table:** URL DB has the table but **no service code reads or writes to it**. URL creation limits are not enforced.

5. **feature_flags.role_id:** References identity service's roles table logically but has no FK constraint. Feature service hardcodes `adminRoleId=1L, userRoleId=2L`.

6. **No Migration Scripts:** There are no Flyway/Liquibase migration scripts for any microservice. The `scripts/init-*.sql` files are manual initialization only.

### 4.3 Entity Differences

| Aspect | Monolith User Entity | Microservices User Entity |
|---|---|---|
| Package | `com.miniurl.entity` | `com.miniurl.identity.entity` |
| Builder | Full builder with all fields | Full builder with all fields |
| Account Lockout Methods | `incrementFailedLoginAttempts()`, `resetFailedLoginAttempts()`, `isAccountLocked()`, `isLockoutExpired()`, `isLoginAttemptAllowed()` | Same methods present |
| getFullName() | Not present | Present (convenience) |
| isAdmin() | Not present | Present (convenience) |
| isActive() | Not present | Present (convenience) |

| Aspect | Monolith Url Entity | Microservices Url Entity |
|---|---|---|
| user field | `@ManyToOne(fetch = LAZY) User user` | `Long userId` (no relationship) |
| Package | `com.miniurl.entity` | `com.miniurl.url.entity` |

---

## 5. Security Analysis

### 5.1 JWT: HMAC-SHA512 → RS256/JWKS

| Aspect | Monolith | Microservices |
|---|---|---|
| Algorithm | HMAC-SHA512 (symmetric) | RS256 (asymmetric) |
| Key Management | Single shared secret from `app.jwt.secret` | RSA 2048-bit key pair, generated at startup |
| Key Rotation | Manual (change env var, all tokens invalidated) | No rotation mechanism (single key, in-memory) |
| Public Key Distribution | N/A (symmetric) | JWKS endpoint at `/.well-known/jwks.json` |
| Library | JJWT (io.jsonwebtoken) | JJWT + Nimbus JOSE+JWT |
| Secret Validation | Requires ≥32 chars at startup | N/A |
| Token Renewal | Via X-Authorization header, 5-min threshold | Not implemented |

**Assessment:** RS256 is architecturally superior for microservices, but the current implementation has no key rotation strategy. If the identity service restarts, all existing tokens become invalid (new key pair generated). This is a **P0 operational concern**.

### 5.2 Authentication Flow Differences

| Security Feature | Monolith | Microservices |
|---|---|---|
| Account Lockout | Enforced at login | **Not enforced** (entity has logic, controller doesn't call it) |
| 2FA Conditional | Checks TWO_FACTOR_AUTH global flag | **Always sends OTP** regardless of flag |
| Anti-enumeration (forgot password) | Same response whether email exists or not | **Different responses** |
| Anti-enumeration (login) | Same response for bad password vs non-existent user | Same response (400 in both cases) |
| Delete Account Auth | Extracts identity from JWT | **Accepts userId from request body** — anyone can delete any account |
| Password Reset Cooldown | 20 min per email | Present |
| OTP Brute-force Protection | 5 attempts per 5 min per identifier | Present |

### 5.3 Rate Limiting

| Aspect | Monolith | Microservices |
|---|---|---|
| Implementation | Bucket4j (in-memory, per-instance) | Redis (gateway-level, distributed) |
| Granularity | Dual-layer: per-IP + per-username/email | Per-route only |
| Login Limit | 100 req/15min IP + 5 req/5min username | 10 req/s replenish at gateway |
| Password Reset Limit | 60 req/hr IP + 3 req/hr email | 5 req/s replenish at gateway |
| OTP Limit | 30 req/15min IP + 5 req/5min identifier | 5 req/s replenish at gateway |
| Signup Limit | 20 req/hr | 5 req/s replenish at gateway |
| URL Creation Limit | 500 req/hr | 10 req/s replenish at gateway |
| Redirect Limit | 5000 req/hr | 50 req/s replenish at gateway |
| General API Limit | 1000 req/hr | 20 req/s replenish at gateway |

**Assessment:** The microservices Redis-based rate limiting is operationally superior (distributed, survives restarts), but the **lack of per-user/per-email granularity** is a security regression. A single attacker can brute-force many usernames from one IP without triggering the per-username limit that exists in the monolith.

### 5.4 CSRF & CORS

| Aspect | Monolith | Microservices |
|---|---|---|
| CSRF | Enabled, ignored for /api/** | Disabled everywhere |
| CORS Origins | From env var `APP_CORS_ALLOWED_ORIGINS` | Hardcoded to localhost:8080, localhost:3000, 127.0.0.1:8080, 127.0.0.1:3000 |
| CORS Credentials | true | true |

**Assessment:** Hardcoded CORS origins will break in any non-localhost deployment. The monolith's env-var approach is correct.

### 5.5 Open Redirect Vulnerability

The monolith's [`RedirectController.isValidRedirectUrl()`](miniurl-monolith/src/main/java/com/miniurl/controller/RedirectController.java:29) validates the redirect target before issuing a 302:

```java
private boolean isValidRedirectUrl(String url) {
    // Blocks javascript:, data:, vbscript:, file:, ftp:, about: protocols
    // Only allows http: and https:
}
```

The microservices [`RedirectController`](redirect-service/src/main/java/com/miniurl/redirect/controller/RedirectController.java:29) has **no such validation**. This is a **P0 security vulnerability** — an attacker who can create a URL with a `javascript:` target could execute XSS via the redirect endpoint.

---

## 6. Redirect Hot-Path Analysis

### 6.1 Path Comparison

**Monolith Flow (`/r/{shortCode}`):**
```
Request → RedirectController.getRedirect()
  → urlService.findByShortCode(shortCode)
    → urlRepository.findByShortCode() [MySQL, indexed]
    → url.setAccessCount(url.getAccessCount() + 1) [synchronous write]
    → urlRepository.save(url)
  → isValidRedirectUrl(originalUrl) [security check]
  → 302 FOUND with Location header
```

**Microservices Flow (`/r/{code}`):**
```
Request → RedirectController.redirect()
  → redirectService.resolveUrl(code)
    → redisTemplate.opsForValue().get("url:cache:{code}") [Redis, 1hr TTL]
    → ON MISS: webClient.get().uri("/internal/urls/resolve/{code}") [HTTP to URL service]
      → urlService.resolveShortCode(code)
        → urlRepository.findByShortCode() [MySQL, indexed]
      → redisTemplate.opsForValue().set("url:cache:{code}", url, 1hr)
  → clickEventProducer.sendClickEvent() [Kafka, async, fire-and-forget]
  → 302 FOUND with Location header
  → NO URL VALIDATION before redirect
```

### 6.2 Performance Characteristics

| Metric | Monolith | Microservices |
|---|---|---|
| Cache Layer | None | Redis (1hr TTL) |
| DB Query per Request | 1 read + 1 write (accessCount) | 0 (cache hit) or 1 read (cache miss) |
| Network Hops | 0 (in-process) | 1 (Redis) or 3 (Redis miss + URL service HTTP + Redis write) |
| Reactive/Non-blocking | No (Servlet) | Yes (WebFlux) |
| Cold Start Latency | ~5-20ms (DB) | ~10-50ms (Redis miss + HTTP + DB) |
| Warm Cache Latency | ~5-20ms (always DB) | ~1-5ms (Redis only) |
| 10k req/sec Target | Limited by DB connection pool (50 max) | Limited by Redis + WebFlux event loop |

### 6.3 Critical Issues

1. **No Redirect URL Validation:** The microservices redirect controller does not validate the target URL before issuing a 302. This is a **P0 open redirect vulnerability**.

2. **Access Count Accuracy:** The monolith increments `accessCount` synchronously. The microservices sends a Kafka event asynchronously. If Kafka is unavailable, click events are silently lost (the producer swallows exceptions).

3. **Cache Invalidation:** There is no cache invalidation when a URL is deleted. A deleted URL remains redirectable for up to 1 hour from Redis cache.

4. **No Circuit Breaker on URL Service Call:** If the URL service is down, the redirect service returns empty (no URL found) rather than failing gracefully with a 503 or fallback page.

5. **Single Point of Failure:** Redis becomes a critical SPOF for the redirect path. If Redis is down, every request becomes a cache miss + HTTP call to URL service, potentially cascading failure.

---

## 7. Event-Driven Behavior

### 7.1 Outbox Pattern Implementation

Both identity-service and url-service implement the outbox pattern:

```
Transaction:
  1. Business operation (save user, create URL, etc.)
  2. OutboxService.saveEvent() [same transaction, MANDATORY propagation]

OutboxRelay (every 5 seconds):
  1. SELECT * FROM outbox WHERE processed = false ORDER BY created_at
  2. For each: kafkaTemplate.send(topic, key, payload)
  3. Mark processed = true
```

### 7.2 Topic Mapping

| Service | Aggregate Type | Kafka Topic |
|---|---|---|
| Identity Service | USER | `notifications` |
| URL Service | URL | `url-events` |
| Redirect Service | (direct) | `click-events` |

### 7.3 Event Type Compatibility

| Event Type (Identity Service sends) | Notification Service handles | Match? |
|---|---|---|
| OTP | OTP | ✓ |
| EMAIL_VERIFICATION | EMAIL_VERIFICATION | ✓ |
| PASSWORD_RESET | PASSWORD_RESET | ✓ |
| WELCOME | WELCOME | ✓ |
| WELCOME_BACK | WELCOME_BACK | ✓ |
| ACCOUNT_DELETION | ACCOUNT_DELETION | ✓ |
| PASSWORD_RESET_CONFIRMATION | PASSWORD_RESET_CONFIRMATION | ✓ |
| PASSWORD_CHANGE_NOTIFICATION | PASSWORD_CHANGE_NOTIFICATION | ✓ |
| INVITE | INVITE | ✓ |
| CONGRATULATIONS | CONGRATULATIONS | ✓ |
| **REGISTRATION_CONGRATS** | **NOT HANDLED** | **P0 BUG** ✗ |

The identity service's [`AuthService`](identity-service/src/main/java/com/miniurl/identity/service/AuthService.java:108) sends `REGISTRATION_CONGRATS` events, but the notification service's [`EmailService.sendEmail()`](notification-service/src/main/java/com/miniurl/notification/service/EmailService.java:53) has no case for it. Registration congratulations emails will never be sent.

### 7.4 Missing Resilience in Notification Service

The monolith's [`EmailService`](miniurl-monolith/src/main/java/com/miniurl/service/EmailService.java:73) uses:
- `@CircuitBreaker(name = "emailService")` — opens after 50% failures
- `@Bulkhead(name = "email")` — max 20 concurrent calls
- `@Retry(name = "emailService")` — 3 attempts with exponential backoff
- Fallback methods that log to console

The microservices [`EmailService`](notification-service/src/main/java/com/miniurl/notification/service/EmailService.java:53) has **none of these**. If the SMTP server is slow or down, the notification service will block, backpressure Kafka, and potentially cause outbox relay failures.

### 7.5 Outbox Relay Issues

1. **No exactly-once semantics:** The relay marks events as processed BEFORE confirming Kafka delivery. If the Kafka send fails after marking, the event is lost.

2. **No retry for failed events:** Failed events are logged but not retried with backoff. They remain `processed=false` and will be retried on the next 5-second cycle indefinitely.

3. **No dead-letter queue:** There's no mechanism to handle poison messages.

4. **Ordering not guaranteed within aggregate:** Events are processed in `created_at` order but Kafka partitions by `aggregateId`, so ordering within an aggregate is preserved only if the partitioner is key-based.

---

## 8. Distributed Systems Risks

### 8.1 Service Discovery

- Eureka server is present for service discovery
- API Gateway routes are hardcoded in [`application.yml`](api-gateway/src/main/resources/application.yml:1) with service URLs
- **Risk:** If Eureka is down after initial connection, gateway may continue routing to stale instances

### 8.2 Data Consistency

| Scenario | Risk |
|---|---|
| User deleted in identity service | URLs in URL service become orphaned (no FK, no cascade) |
| URL created, outbox event lost | Analytics/notification services never learn about it |
| Cache not invalidated on URL delete | Stale redirects served for up to 1 hour |
| Feature flag toggled | Cache invalidation is local to feature-service instance; other instances may serve stale data for up to 10 min |

### 8.3 Failure Cascades

| Failure | Impact |
|---|---|
| Identity service down | No login, no signup, no profile access |
| URL service down | No URL CRUD, redirect cache misses fail |
| Redis down | All redirects become cache-miss + HTTP call (latency spike, potential cascade) |
| Kafka down | Outbox events accumulate, click events lost, emails delayed |
| Notification service down | Emails not sent, outbox events accumulate in identity service |
| Feature service down | Feature flags unavailable, 2FA check fails, self-invite check fails |
| Eureka down | New instances not discovered, eventual routing failures |

### 8.4 Distributed Transaction Gaps

The monolith performs these operations atomically:
1. Create user + send welcome email
2. Delete user + soft-delete + send notification
3. Create URL + update usage limits

In microservices, these are split across services with eventual consistency. There are **no compensating transactions or sagas** for rollback scenarios.

---

## 9. Observability & Operations

### 9.1 Health Checks

| Service | Health Endpoint | Details |
|---|---|---|
| Monolith | GET /api/health | Returns `{success: true, message: "Service is running"}` |
| API Gateway | /actuator/health | Standard actuator |
| Identity Service | /actuator/health | Standard actuator |
| URL Service | /actuator/health | Standard actuator |
| Redirect Service | /actuator/health | Standard actuator |
| Feature Service | /actuator/health | Standard actuator |
| Notification Service | /actuator/health | Standard actuator |
| Analytics Service | /actuator/health | Standard actuator |

### 9.2 Metrics

| Capability | Monolith | Microservices |
|---|---|---|
| Circuit Breaker Metrics | Exposed via actuator | **Not applicable** (no circuit breakers) |
| Bulkhead Metrics | Exposed via actuator | **Not applicable** (no bulkheads) |
| Retry Metrics | Exposed via actuator | **Not applicable** (no retries) |
| JVM Metrics | Via actuator | Via actuator |
| Kafka Metrics | N/A | Not configured |
| Redis Metrics | N/A | Not configured |
| HTTP Metrics | Via actuator | Via actuator |

### 9.3 Distributed Tracing

A [`TracingConfig.java`](common/src/main/java/com/miniurl/common/config/TracingConfig.java:1) exists in the common module but its contents are unknown. No trace propagation headers are visible in the gateway or service configurations.

### 9.4 Logging

- Monolith: Profile-specific logging (DEBUG in dev, INFO in prod), structured around `com.miniurl` package
- Microservices: Default Spring Boot logging, no consistent log format across services
- **No correlation ID propagation** visible in gateway filters or service configs

### 9.5 K8s/Helm Readiness

- K8s manifests exist per service with deployments and services
- HPA configured for CPU/memory-based scaling
- Helm charts with dev/prod values
- **Missing:** Readiness probes that check downstream dependencies (DB, Redis, Kafka)
- **Missing:** PodDisruptionBudget for graceful eviction
- **Missing:** NetworkPolicy for service-to-service communication restrictions

---

## 10. Test Coverage Gap Analysis

### 10.1 Monolith Test Suite (~200+ tests)

| Test Category | Test Classes | Test Count (est.) |
|---|---|---|
| Integration Tests | AuthenticationIntegrationTest, UrlCrudIntegrationTest, TwoFactorAuthIntegrationTest, FeatureFlagIntegrationTest, SecurityFeaturesIntegrationTest, EmailIntegrationTest, TwoFactorAuthEdgeCaseIntegrationTest, PerEmailRateLimitIntegrationTest | ~80 |
| Service Tests | FeatureFlagServiceTest (263 lines), GlobalFlagServiceTest (283 lines), EmailInviteServicePaginationTest, UrlCreationMinuteTrackerTest, UrlUsageLimitServiceLimitTest | ~50 |
| Entity Tests | UserLockoutTest (168 lines), EmailInviteTest, EntityTest, UrlUsageLimitTest | ~30 |
| Config Tests | ConfigTest | ~10 |
| DTO Tests | DtoTest | ~10 |
| Exception Tests | ExceptionTest | ~5 |
| Util Tests | JwtUtilTest, ValidationUtilsTest | ~15 |
| Repository Tests | (in repository/ directory) | ~10 |

### 10.2 Microservices Test Suite (~70 tests)

| Service | Test Classes | Test Count (est.) |
|---|---|---|
| Identity Service | JwtServiceTest (96 lines), KeyServiceTest (64 lines) | ~10 |
| URL Service | SnowflakeIdGeneratorTest (72 lines) | ~5 |
| Redirect Service | RedirectServiceTest (165 lines) | ~8 |
| Feature Service | FeatureFlagServiceTest (152 lines) | ~8 |
| Notification Service | **NONE** | 0 |
| Analytics Service | **NONE** | 0 |
| API Gateway | **NONE** | 0 |
| Eureka Server | **NONE** | 0 |

### 10.3 Missing Test Categories (Microservices)

| Test Type | Monolith Has | Microservices Has | Gap |
|---|---|---|---|
| Integration Tests (full context) | 8 classes | **0** | **P0** |
| AuthService Tests | Via integration | **0** | **P0** |
| UrlService Tests | Via integration | **0** | **P0** |
| Controller Tests | Via integration | **0** | **P0** |
| Repository Tests | Present | **0** | **P0** |
| Security Tests | Present | **0** | **P0** |
| Email/Notification Tests | Present (GreenMail) | **0** | **P0** |
| Rate Limiting Tests | Present | **0** | **P0** |
| Outbox Tests | N/A | **0** | **P0** |
| Kafka Integration Tests | N/A | **0** | **P0** |
| Redis Integration Tests | N/A | **0** (RedirectServiceTest mocks Redis) | **P1** |
| Feature Flag Integration | Present | **0** | **P0** |
| Global Flag Tests | Present | **0** | **P0** |
| Account Lockout Tests | Present (UserLockoutTest) | **0** | **P0** |
| 2FA Flow Tests | Present | **0** | **P0** |
| DTO Validation Tests | Present | **0** | **P1** |
| Exception Handler Tests | Present | **0** | **P1** |

### 10.4 Test Quality Assessment

**Monolith tests** demonstrate:
- Full Spring context integration tests with MockMvc
- GreenMail for real email testing
- CSRF token handling in tests
- Unique test data per run (timestamps in usernames)
- Proper cleanup in @BeforeEach/@AfterEach
- Comprehensive 2FA flow testing (enabled, disabled, wrong OTP, no prior login, resend, repeated calls)
- URL ownership validation testing
- Rate limiting behavior verification

**Microservices tests** demonstrate:
- Basic unit tests with Mockito mocks
- Reactive testing with StepVerifier (RedirectServiceTest)
- Thread-safety testing (SnowflakeIdGeneratorTest)
- Redis caching logic testing (FeatureFlagServiceTest)
- JWT signing/validation testing (JwtServiceTest)
- Key generation testing (KeyServiceTest)

**Critical observation:** The microservices tests are all unit tests with mocked dependencies. There are **zero integration tests** that verify actual service-to-service communication, database operations, Kafka message flow, or Redis caching behavior in a real environment.

---

## 11. Required Verification Commands

Before considering this migration ready, the following must pass:

### 11.1 Security Verification

```bash
# Verify JWKS endpoint returns valid keys
curl -s http://localhost:8081/.well-known/jwks.json | jq .

# Verify open redirect protection
curl -v -o /dev/null "http://localhost:8080/r/VALIDCODE" 2>&1 | grep "< Location"

# Verify account lockout after 5 failed logins
for i in {1..6}; do
  curl -s -X POST http://localhost:8080/api/auth/login \
    -H "Content-Type: application/json" \
    -d '{"username":"testuser","password":"wrong"}' | jq .
done

# Verify anti-enumeration on forgot-password
curl -s -X POST http://localhost:8080/api/auth/forgot-password \
  -H "Content-Type: application/json" \
  -d '{"email":"exists@test.com"}' | jq .
curl -s -X POST http://localhost:8080/api/auth/forgot-password \
  -H "Content-Type: application/json" \
  -d '{"email":"doesnotexist@test.com"}' | jq .
# Both responses must be identical

# Verify delete-account requires JWT, not request body userId
curl -s -X POST http://localhost:8080/api/auth/delete-account \
  -H "Authorization: Bearer <OTHER_USER_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"userId":1}' | jq .
# Must return 401/403
```

### 11.2 Functional Verification

```bash
# Verify all admin endpoints exist
curl -s http://localhost:8080/api/admin/users | jq .
curl -s http://localhost:8080/api/admin/stats | jq .
curl -s http://localhost:8080/api/admin/email-invites | jq .

# Verify profile endpoints
curl -s http://localhost:8080/api/profile | jq .

# Verify settings endpoints
curl -s http://localhost:8080/api/settings/export | jq .

# Verify self-invite endpoint
curl -s -X POST http://localhost:8080/api/self-invite/send \
  -d "email=test@example.com&baseUrl=http://localhost:8080" | jq .

# Verify public feature flags
curl -s http://localhost:8080/api/features/global | jq .
```

### 11.3 Event-Driven Verification

```bash
# Verify outbox relay processes events
# Check Kafka topic for notification events
kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic notifications --from-beginning --max-messages 5

# Verify click events are published
kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic click-events --from-beginning --max-messages 5

# Verify REGISTRATION_CONGRATS events are handled
# Send a signup request and check notification service logs
```

### 11.4 Performance Verification

```bash
# Load test redirect endpoint (target: 10k req/sec)
wrk -t12 -c400 -d30s http://localhost:8080/r/TESTCODE

# Verify Redis cache hit rate
redis-cli INFO stats | grep keyspace

# Verify no connection pool exhaustion under load
curl -s http://localhost:8080/actuator/metrics/hikaricp.connections.active | jq .
```

---

## 12. Prioritized Gap List

### P0 — BLOCKING (Must Fix Before Production)

| # | Gap | Location | Impact |
|---|---|---|---|
| P0-1 | **No open redirect validation** in redirect controller | [`RedirectController.java`](redirect-service/src/main/java/com/miniurl/redirect/controller/RedirectController.java:29) | XSS vulnerability |
| P0-2 | **Account lockout not enforced** at login | [`AuthController.java`](identity-service/src/main/java/com/miniurl/identity/controller/AuthController.java:89) | Brute-force vulnerability |
| P0-3 | **Delete account accepts userId from body** instead of JWT | [`AuthController.java`](identity-service/src/main/java/com/miniurl/identity/controller/AuthController.java:125) | Anyone can delete any account |
| P0-4 | **verify-email calls wrong method** (verifyEmailInviteToken vs verifyResetPasswordToken) | [`AuthController.java`](identity-service/src/main/java/com/miniurl/identity/controller/AuthController.java:71) | Password reset flow broken |
| P0-5 | **No anti-enumeration** on forgot-password | [`AuthController.java`](identity-service/src/main/java/com/miniurl/identity/controller/AuthController.java:77) | Email enumeration vulnerability |
| P0-6 | **Missing AdminController** — all admin endpoints absent | identity-service | No user management, no stats, no invite management |
| P0-7 | **Missing ProfileController** — /api/profile endpoints absent | identity-service | Users cannot view/update profile |
| P0-8 | **Missing SettingsController** — export and delete-account absent | identity-service | Users cannot export data or delete account |
| P0-9 | **Missing SelfInviteController** — /api/self-invite/send absent | identity-service | Public signup flow broken |
| P0-10 | **Missing public /api/features/global** endpoint | feature-service + gateway | Frontend cannot check global flags without auth |
| P0-11 | **Missing /api/features** for authenticated users | feature-service | Frontend cannot get role-based features |
| P0-12 | **REGISTRATION_CONGRATS event not handled** | [`EmailService.java`](notification-service/src/main/java/com/miniurl/notification/service/EmailService.java:53) | Welcome emails silently dropped |
| P0-13 | **No integration tests** in any microservice | All services | Zero confidence in service integration |
| P0-14 | **Key regeneration on restart** invalidates all tokens | [`KeyService.java`](identity-service/src/main/java/com/miniurl/identity/service/KeyService.java:26) | All users logged out on deploy |
| P0-15 | **No cache invalidation on URL delete** | redirect-service + url-service | Deleted URLs still redirectable for 1hr |

### P1 — HIGH (Must Fix Before Production)

| # | Gap | Location | Impact |
|---|---|---|---|
| P1-1 | No Resilience4j patterns in notification EmailService | [`EmailService.java`](notification-service/src/main/java/com/miniurl/notification/service/EmailService.java:53) | SMTP failures cascade to Kafka backpressure |
| P1-2 | Rate limiting lacks per-user granularity | [`application.yml`](api-gateway/src/main/resources/application.yml:1) | Brute-force protection weakened |
| P1-3 | Audit logging schema exists but no code writes to it | identity-service | No audit trail for admin actions |
| P1-4 | URL usage limits table exists but no enforcement code | url-service | No per-user URL creation limits |
| P1-5 | Hardcoded CORS origins | [`SecurityConfig.java`](identity-service/src/main/java/com/miniurl/identity/config/SecurityConfig.java:66) | Won't work outside localhost |
| P1-6 | Missing @Valid on CreateUrlRequest in URL controller | [`UrlController.java`](url-service/src/main/java/com/miniurl/url/controller/UrlController.java:25) | Invalid URLs accepted |
| P1-7 | URL pagination uses POST instead of GET | [`UrlController.java`](url-service/src/main/java/com/miniurl/url/controller/UrlController.java:40) | REST anti-pattern |
| P1-8 | Feature service returns raw DTOs instead of ApiResponse | [`FeatureController.java`](feature-service/src/main/java/com/miniurl/feature/controller/FeatureController.java:21) | Inconsistent API contract |
| P1-9 | No HealthController in identity service | identity-service | No custom health check |
| P1-10 | Outbox relay marks events processed before Kafka ack | [`OutboxRelay.java`](identity-service/src/main/java/com/miniurl/identity/service/OutboxRelay.java:54) | Potential event loss |
| P1-11 | No dead-letter queue for failed outbox events | Both outbox relays | Poison messages retried indefinitely |
| P1-12 | No correlation ID propagation | All services | Cannot trace requests across services |

### P2 — MEDIUM (Should Fix Before Production)

| # | Gap | Location | Impact |
|---|---|---|---|
| P2-1 | Feature toggle uses PATCH instead of PUT | [`FeatureController.java`](feature-service/src/main/java/com/miniurl/feature/controller/FeatureController.java:36) | Inconsistent with monolith |
| P2-2 | Admin feature paths changed (/api/admin/features → /api/features) | Gateway routes | Frontend URL changes needed |
| P2-3 | Global flags at different path (/api/global-flags vs /api/admin/features/global) | [`GlobalFlagController.java`](feature-service/src/main/java/com/miniurl/feature/controller/GlobalFlagController.java:12) | Frontend URL changes needed |
| P2-4 | Login always sends OTP regardless of 2FA flag | [`AuthController.java`](identity-service/src/main/java/com/miniurl/identity/controller/AuthController.java:89) | Changed user experience |
| P2-5 | Signup returns JWT immediately instead of success message | [`AuthController.java`](identity-service/src/main/java/com/miniurl/identity/controller/AuthController.java:44) | Changed API contract |
| P2-6 | No Flyway/Liquibase migration scripts | All services | Manual DB initialization only |
| P2-7 | No PodDisruptionBudget in K8s configs | k8s/ | Unclean pod eviction during scaling |
| P2-8 | No NetworkPolicy in K8s configs | k8s/ | No service-to-service traffic restrictions |
| P2-9 | ClickEventProducer swallows Kafka exceptions | [`ClickEventProducer.java`](redirect-service/src/main/java/com/miniurl/redirect/producer/ClickEventProducer.java:20) | Silent data loss |
| P2-10 | No distributed tracing configuration | All services | Cannot trace requests end-to-end |

---

## Appendix A: File Reference Index

### Monolith Key Files
- [`AuthController.java`](miniurl-monolith/src/main/java/com/miniurl/controller/AuthController.java:1) — 787 lines, all auth endpoints
- [`AdminController.java`](miniurl-monolith/src/main/java/com/miniurl/controller/AdminController.java:1) — 391 lines, admin CRUD
- [`UrlController.java`](miniurl-monolith/src/main/java/com/miniurl/controller/UrlController.java:1) — 178 lines, URL CRUD
- [`RedirectController.java`](miniurl-monolith/src/main/java/com/miniurl/controller/RedirectController.java:1) — 77 lines, redirect + validation
- [`ProfileController.java`](miniurl-monolith/src/main/java/com/miniurl/controller/ProfileController.java:1) — 160 lines
- [`SettingsController.java`](miniurl-monolith/src/main/java/com/miniurl/controller/SettingsController.java:1) — 153 lines
- [`SelfInviteController.java`](miniurl-monolith/src/main/java/com/miniurl/controller/SelfInviteController.java:1) — 123 lines
- [`FeatureFlagController.java`](miniurl-monolith/src/main/java/com/miniurl/controller/FeatureFlagController.java:1) — 352 lines
- [`FeatureFlagPublicController.java`](miniurl-monolith/src/main/java/com/miniurl/controller/FeatureFlagPublicController.java:1) — 149 lines
- [`EmailInviteController.java`](miniurl-monolith/src/main/java/com/miniurl/controller/EmailInviteController.java:1) — 187 lines
- [`AuthService.java`](miniurl-monolith/src/main/java/com/miniurl/service/AuthService.java:1) — 597 lines
- [`UrlService.java`](miniurl-monolith/src/main/java/com/miniurl/service/UrlService.java:1) — 387 lines
- [`EmailService.java`](miniurl-monolith/src/main/java/com/miniurl/service/EmailService.java:1) — 619 lines, with Resilience4j
- [`SecurityConfig.java`](miniurl-monolith/src/main/java/com/miniurl/config/SecurityConfig.java:1) — 208 lines
- [`RateLimitingFilter.java`](miniurl-monolith/src/main/java/com/miniurl/config/RateLimitingFilter.java:1) — 441 lines, Bucket4j dual-layer
- [`JwtAuthenticationFilter.java`](miniurl-monolith/src/main/java/com/miniurl/config/JwtAuthenticationFilter.java:1) — 130 lines
- [`GlobalExceptionHandler.java`](miniurl-monolith/src/main/java/com/miniurl/config/GlobalExceptionHandler.java:1) — 164 lines
- [`JwtUtil.java`](common/src/main/java/com/miniurl/util/JwtUtil.java:1) — 206 lines, HMAC-SHA512
- [`init-db.sql`](scripts/init-db.sql:1) — Monolith DB schema

### Microservices Key Files
- [`SecurityConfig.java`](api-gateway/src/main/java/com/miniurl/gateway/config/SecurityConfig.java:1) — Gateway security
- [`application.yml`](api-gateway/src/main/resources/application.yml:1) — Gateway routes + rate limits
- [`AuthController.java`](identity-service/src/main/java/com/miniurl/identity/controller/AuthController.java:1) — 130 lines
- [`AuthService.java`](identity-service/src/main/java/com/miniurl/identity/service/AuthService.java:1) — 667 lines
- [`JwtService.java`](identity-service/src/main/java/com/miniurl/identity/service/JwtService.java:1) — 53 lines, RS256
- [`KeyService.java`](identity-service/src/main/java/com/miniurl/identity/service/KeyService.java:1) — 67 lines, RSA key pair
- [`OutboxService.java`](identity-service/src/main/java/com/miniurl/identity/service/OutboxService.java:1) — 43 lines
- [`OutboxRelay.java`](identity-service/src/main/java/com/miniurl/identity/service/OutboxRelay.java:1) — 70 lines
- [`UrlController.java`](url-service/src/main/java/com/miniurl/url/controller/UrlController.java:1) — 70 lines
- [`UrlService.java`](url-service/src/main/java/com/miniurl/url/service/UrlService.java:1) — 330 lines
- [`InternalUrlController.java`](url-service/src/main/java/com/miniurl/url/controller/InternalUrlController.java:1) — 26 lines
- [`SnowflakeIdGenerator.java`](url-service/src/main/java/com/miniurl/url/util/SnowflakeIdGenerator.java:1) — 68 lines
- [`Base62.java`](url-service/src/main/java/com/miniurl/url/util/Base62.java:1) — 39 lines
- [`RedirectController.java`](redirect-service/src/main/java/com/miniurl/redirect/controller/RedirectController.java:1) — 58 lines
- [`RedirectService.java`](redirect-service/src/main/java/com/miniurl/redirect/service/RedirectService.java:1) — 67 lines
- [`ClickEventProducer.java`](redirect-service/src/main/java/com/miniurl/redirect/producer/ClickEventProducer.java:1) — 29 lines
- [`FeatureController.java`](feature-service/src/main/java/com/miniurl/feature/controller/FeatureController.java:1) — 66 lines
- [`FeatureFlagService.java`](feature-service/src/main/java/com/miniurl/feature/service/FeatureFlagService.java:1) — 171 lines
- [`GlobalFlagController.java`](feature-service/src/main/java/com/miniurl/feature/controller/GlobalFlagController.java:1) — 56 lines
- [`GlobalFlagService.java`](feature-service/src/main/java/com/miniurl/feature/service/GlobalFlagService.java:1) — 147 lines
- [`NotificationConsumer.java`](notification-service/src/main/java/com/miniurl/notification/kafka/NotificationConsumer.java:1) — 31 lines
- [`EmailService.java`](notification-service/src/main/java/com/miniurl/notification/service/EmailService.java:1) — 133 lines
- [`AnalyticsConsumer.java`](analytics-service/src/main/java/com/miniurl/analytics/kafka/AnalyticsConsumer.java:1) — 38 lines

---

*End of Migration Audit Report*
