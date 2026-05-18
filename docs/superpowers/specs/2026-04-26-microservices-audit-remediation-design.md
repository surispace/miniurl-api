# Microservices Audit & Remediation Design

**Date:** 2026-04-26
**Project:** miniurl-api (URL Shortener Microservices)

## Background

This project was migrated from a monolith to microservices architecture. An audit revealed several critical bugs that break core functionality, build/packaging gaps, missing tests, and stale deployment artifacts. This spec covers the full remediation.

## Approach: Vertical by Service

Each service is fixed completely (bugs + tests + cleanup) before moving to the next, in dependency order:
1. Common module
2. Identity Service
3. URL Service
4. Analytics Service
5. Redirect Service (verify only)
6. Feature Service (verify only)
7. Build & Config (parent POM, Docker, K8s)
8. API Gateway (verify only)
9. Notification Service (verify only)

## Section 1: Common Module Fixes

**Bug:** `ClickEvent.java` and `UrlEvent.java` are physically at `common/src/main/java/com/miniurl/dto/` but declare `package com.miniurl.common.dto;`. Maven compilation fails.

**Fix:**
- Move `ClickEvent.java` → `common/src/main/java/com/miniurl/common/dto/ClickEvent.java`
- Move `UrlEvent.java` → `common/src/main/java/com/miniurl/common/dto/UrlEvent.java`
- Verify all cross-service imports reference the corrected paths

## Section 2: Identity Service

### 2.1 KeyService JWKS Bug (Critical)

**Problem:** `getPublicJWKSet()` generates a new RSA key pair on every request instead of returning a stored public key. API Gateway fetches a different key each time → JWT validation always fails.

**Fix:**
- Generate a single `RSAKey` at bean construction time using `RSAKeyGenerator`
- Store as a private field
- `getJWKSet()` returns the stored key's public JWK set
- `getPrivateKey()` returns the stored key's private key for JWT signing
- Remove the broken `loadOrGenerateKeyPair()` method

### 2.2 Duplicate Common Classes Cleanup

**Problem:** 24+ DTOs, 6 exception classes, and 2 utility classes exist as copies under `com.miniurl.dto.*`, `com.miniurl.exception.*`, `com.miniurl.util.*` in the identity-service source tree. Since identity-service depends on the common module, these cause potential classpath conflicts and maintenance burden.

**Fix:** Delete all duplicate files from identity-service's own source tree. Update any imports that reference the local copies to use the common module versions.

## Section 3: URL Service — YAML Duplicate Key

**Problem:** Duplicate `server:` key in `application.yml`. The second `server:` block (with `worker-id`) overwrites the first (with `port`), losing `server.port`.

**Fix:** Merge `worker-id` into the first `server:` block as a single YAML map entry.

## Section 4: Analytics Service — Kafka Topic Mismatch

**Problem:** Redirect service producer sends to `click-events` topic, but analytics consumer listens on `clicks` topic. No click events reach analytics.

**Fix:** Update `AnalyticsConsumer.java`: `@KafkaListener(topics = "click-events")`.

## Section 5: Build & Config

### 5.1 Parent POM

**Problem:** `eureka-server` module exists but is not listed in parent `pom.xml` `<modules>`.

**Fix:** Add `<module>eureka-server</module>` to the modules list.

### 5.2 Docker

**Problem:** Dockerfiles are monolith-focused. `docker-compose.yml` has only infrastructure (no microservice app containers). `docker-compose.dev.yml` is stale.

**Fix:**
- Create a multi-stage `Dockerfile` per service (or a single multi-stage for all services)
- Add microservice app containers to `docker-compose.yml` with proper depends_on and health checks
- Remove or update stale docker-compose files

### 5.3 Kubernetes

**Problem:** K8s manifests exist but need to be verified against the fixed code.

**Fix:** Verify all K8s manifests at `k8s/services/` are consistent with current service configurations.

## Section 6: TDD Implementation

### 6.1 Port Monolith Tests

The monolith has 20 test classes organized as unit and integration tests. Each will be ported to its matching microservice module:

| Monolith Test | Target Service |
|---|---|
| AuthenticationIntegrationTest | identity-service |
| EmailIntegrationTest | identity-service |
| TwoFactorAuthIntegrationTest | identity-service |
| TwoFactorAuthEdgeCaseIntegrationTest | identity-service |
| SecurityFeaturesIntegrationTest | identity-service |
| JwtUtilTest | identity-service |
| DtoTest | common |
| UserLockoutTest | identity-service |
| UrlCrudIntegrationTest | url-service |
| UrlUsageLimitTest | url-service |
| UrlUsageLimitServiceLimitTest | url-service |
| UrlCreationMinuteTrackerTest | url-service |
| FeatureFlagServiceTest | feature-service |
| GlobalFlagServiceTest | feature-service |
| FeatureFlagIntegrationTest | feature-service |
| ValidationUtilsTest | common |
| ExceptionTest | common |
| EntityTest | common |
| EmailInviteTest | identity-service |
| EmailInviteServicePaginationTest | identity-service |
| PerEmailRateLimitIntegrationTest | identity-service |

### 6.2 New TDD Tests

New tests for microservice-specific code not covered by ported monolith tests:

| Service | New Test | Focus |
|---|---|---|
| identity-service | KeyServiceTest | Key generation, JWK set retrieval |
| identity-service | JwtServiceTest | Token signing/validation with RSA |
| identity-service | AuthControllerTest | Signup/login flow |
| url-service | SnowflakeIdGeneratorTest | ID uniqueness, worker-id, sequence |
| url-service | UrlServiceTest | Alias validation, SSRF protection |
| redirect-service | RedirectServiceTest | Redis-first resolution, cache miss fallback |
| analytics-service | AnalyticsConsumerTest | Kafka consumption, persistence |
| notification-service | NotificationConsumerTest | Kafka consumption, email dispatch |
| api-gateway | GatewayRouteTest | Route matching, rate limiting |

## Section 7: Verification

After all fixes:
1. Full `mvn clean install` succeeds
2. All tests pass
3. Eureka server starts and registers all services
4. JWT auth flow works end-to-end (login → gateway validates)
5. URL redirect flow works (create URL → get short code → resolve)
6. Kafka events flow (redirect → click-event → analytics persistence)
7. Docker compose builds and starts all service containers

## Success Criteria

- [ ] All 10 audit issues resolved
- [ ] All 20+ monolith tests ported and passing in microservice modules
- [ ] New microservice-specific tests written and passing
- [ ] `mvn clean install` passes
- [ ] Eureka server included in build
- [ ] JWKS endpoint returns consistent public key
- [ ] Kafka topics aligned between producers and consumers
- [ ] Docker compose can run full stack
- [ ] K8s manifests verified
- [ ] All duplicate classes removed
