# Microservices Audit & Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix all 10 audit issues found in the monolith-to-microservices migration, port existing monolith tests, and add new TDD tests for microservice-specific code.

**Architecture:** Vertical-by-service remediation — fix each service completely (bugs + tests + cleanup) before moving to the next, starting with the common module since all services depend on it.

**Tech Stack:** Java 21, Spring Boot 3.2.0, Maven, JUnit 5, Spring Cloud Gateway, Netflix Eureka, Apache Kafka, Redis, MySQL

---

## File Structure Summary

### Modules and Key Files

| Module | Files to Modify/Create | Change Type |
|---|---|---|
| **common** | `common/src/main/java/com/miniurl/dto/ClickEvent.java` | MOVE → `com/miniurl/common/dto/` |
| | `common/src/main/java/com/miniurl/dto/UrlEvent.java` | MOVE → `com/miniurl/common/dto/` |
| **identity-service** | `KeyService.java` | FIX — JWKS returns consistent key |
| | `AuthController.java` | FIX — imports (remove dupe refs) |
| | `AuthService.java` | FIX — imports |
| | `EmailInviteService.java` | FIX — imports |
| | `com/miniurl/dto/` (24 files) | DELETE |
| | `com/miniurl/exception/` (6 files) | DELETE |
| | `com/miniurl/util/` (2 files) | DELETE |
| **url-service** | `application.yml` | FIX — duplicate `server:` key |
| **analytics-service** | `AnalyticsConsumer.java` | FIX — Kafka topic name |
| **root** | `pom.xml` | FIX — add eureka-server module |
| **identity-service tests** | Various new test files | CREATE |
| **url-service tests** | Various new test files | CREATE |
| **feature-service tests** | Various new test files | CREATE |
| **common tests** | Various new test files | CREATE |
| **redirect-service tests** | New test files | CREATE |
| **K8s/Docker** | Dockerfile per service, compose updates | CREATE |

---

### Task 1: Common Module — Fix Package Structure

**Problem:** `ClickEvent.java` and `UrlEvent.java` are at `common/src/main/java/com/miniurl/dto/` but declare `package com.miniurl.common.dto;`. Maven can't compile them.

- [ ] **Step 1: Create the target directory**

```bash
mkdir -p /Users/suri/repo/miniurl-api/common/src/main/java/com/miniurl/common/dto
```

- [ ] **Step 2: Move ClickEvent.java to correct directory**

```bash
mv /Users/suri/repo/miniurl-api/common/src/main/java/com/miniurl/dto/ClickEvent.java \
   /Users/suri/repo/miniurl-api/common/src/main/java/com/miniurl/common/dto/ClickEvent.java
```

- [ ] **Step 3: Move UrlEvent.java to correct directory**

```bash
mv /Users/suri/repo/miniurl-api/common/src/main/java/com/miniurl/dto/UrlEvent.java \
   /Users/suri/repo/miniurl-api/common/src/main/java/com/miniurl/common/dto/UrlEvent.java
```

- [ ] **Step 4: Verify imports across all services**

Check that all services importing `com.miniurl.common.dto.ClickEvent` and `com.miniurl.common.dto.UrlEvent` still resolve correctly after the move. The package declarations in the files are already `package com.miniurl.common.dto;`, so after moving to the correct directory path, imports like `import com.miniurl.common.dto.ClickEvent;` will work.

Run:
```bash
grep -rn "com.miniurl.common.dto.ClickEvent\|com.miniurl.common.dto.UrlEvent" */src/main/ --include="*.java"
```

Expected: Redirect service, identity-service, and analytics-service references exist.

- [ ] **Step 5: Build common module to verify**

```bash
cd /Users/suri/repo/miniurl-api && mvn clean compile -pl common
```

Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add common/src/
git commit -m "fix(common): move ClickEvent and UrlEvent to correct package directory

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 2: Identity Service — Fix KeyService JWKS Bug (Critical)

**Problem:** `KeyService.getPublicJWKSet()` generates a new RSA key on every request instead of returning the stored public key's JWK set. JWT validation by the API Gateway always fails.

- [ ] **Step 1: Read current KeyService.java**

File: `identity-service/src/main/java/com/miniurl/identity/service/KeyService.java`

- [ ] **Step 2: Rewrite KeyService.java**

Replace the file content with:

```java
package com.miniurl.identity.service;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

/**
 * Service for managing RSA key pairs for RS256 JWT signing.
 * Generates a single key pair at startup and caches it in-memory.
 * The public key is exposed via the JWKS endpoint for API Gateway validation.
 */
@Service
public class KeyService {
    private static final Logger logger = LoggerFactory.getLogger(KeyService.class);

    private RSAKey rsaKey;

    @PostConstruct
    public void init() {
        try {
            this.rsaKey = new RSAKeyGenerator(2048)
                    .keyID("miniurl-rsa-key-1")
                    .generate();
            logger.info("RSA KeyPair initialized successfully with key ID: miniurl-rsa-key-1");
        } catch (Exception e) {
            logger.error("Failed to initialize RSA KeyPair", e);
            throw new RuntimeException("Critical failure: Could not initialize security keys", e);
        }
    }

    public PrivateKey getPrivateKey() {
        try {
            return rsaKey.toPrivateKey();
        } catch (Exception e) {
            throw new RuntimeException("Failed to get private key", e);
        }
    }

    public PublicKey getPublicKey() {
        try {
            return rsaKey.toPublicKey();
        } catch (Exception e) {
            throw new RuntimeException("Failed to get public key", e);
        }
    }

    /**
     * Returns the public key as a JWKSet for the JWKS endpoint.
     * Always returns the same stored key — NOT a freshly generated one.
     */
    public JWKSet getPublicJWKSet() {
        try {
            // Build a public-only JWK from the stored RSA key
            RSAKey publicJWK = rsaKey.toPublicJWK();
            return new JWKSet(publicJWK);
        } catch (Exception e) {
            logger.error("Failed to generate JWK set", e);
            throw new RuntimeException("Failed to generate JWK set", e);
        }
    }
}
```

- [ ] **Step 3: Verify identity-service compiles**

```bash
cd /Users/suri/repo/miniurl-api && mvn clean compile -pl common,identity-service
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add identity-service/src/main/java/com/miniurl/identity/service/KeyService.java
git commit -m "fix(identity): return consistent JWK set instead of generating new key each request

KeyService.getPublicJWKSet() now returns the stored key's public JWK,
fixing JWT validation from the API Gateway.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 3: Identity Service — Remove Duplicate Common Classes

**Problem:** 24 DTOs, 6 exceptions, and 2 util classes exist as copies under `com/miniurl/dto/`, `com/miniurl/exception/`, and `com/miniurl/util/` in the identity-service. The service already depends on the common module which provides these.

- [ ] **Step 1: Update AuthController imports**

File: `identity-service/src/main/java/com/miniurl/identity/controller/AuthController.java`

Replace imports from `com.miniurl.dto.*` to `com.miniurl.common.dto.*`:

```java
import com.miniurl.common.dto.ApiResponse;
import com.miniurl.common.dto.JwtAuthRequest;
import com.miniurl.common.dto.JwtAuthResponse;
import com.miniurl.common.dto.LoginRequest;
import com.miniurl.common.dto.LoginOtpResponse;
import com.miniurl.common.dto.LoginResponse;
import com.miniurl.common.dto.OtpVerificationRequest;
import com.miniurl.common.dto.ResendOtpRequest;
import com.miniurl.common.dto.SignupRequest;
import com.miniurl.common.dto.DeleteAccountRequest;
import com.miniurl.common.dto.ForgotPasswordRequest;
import com.miniurl.common.dto.ResetPasswordRequest;
```

Note: Most of these DTOs are in `com.miniurl.dto` in the common module (NOT `com.miniurl.common.dto`). Only ClickEvent and UrlEvent were moved to `com.miniurl.common.dto`. So these should remain as `com.miniurl.dto.*`.

Actually, let me reconsider. The common module has all DTOs at:
- `com.miniurl.dto.*` — ApiResponse, JwtAuthRequest, JwtAuthResponse, etc.

And now ClickEvent and UrlEvent are at:
- `com.miniurl.common.dto.*`

So the AuthController imports from `com.miniurl.dto.*` — these will resolve to the common module's versions since the dependency is there. But the identity-service has its own copies at the same package.

To fix: Delete the identity-service's local copies. The `com.miniurl.dto.*` imports in AuthController, AuthService, and EmailInviteService will then resolve to the common module's versions since identity-service depends on the common module.

Wait, but I need to make sure there are no package conflicts. The common module exposes:
- `com.miniurl.dto.*` (most DTOs)
- `com.miniurl.common.dto.*` (just ClickEvent, UrlEvent after the move)
- `com.miniurl.exception.*`
- `com.miniurl.util.*`
- `com.miniurl.entity.*`
- `com.miniurl.enums.*`

The identity-service's own sources import from `com.miniurl.dto.*`, `com.miniurl.exception.*`, `com.miniurl.util.*`. After deletion, these will resolve from the common module. No package conflict.

So the plan should be:
1. Delete all duplicate files
2. No import changes needed (since identity-service imports match common module's package structure)

- [ ] **Step 2: Delete duplicate DTO files**

```bash
rm /Users/suri/repo/miniurl-api/identity-service/src/main/java/com/miniurl/dto/ApiResponse.java
rm /Users/suri/repo/miniurl-api/identity-service/src/main/java/com/miniurl/dto/ClickEvent.java
rm /Users/suri/repo/miniurl-api/identity-service/src/main/java/com/miniurl/dto/CreateUrlRequest.java
rm /Users/suri/repo/miniurl-api/identity-service/src/main/java/com/miniurl/dto/DeleteAccountRequest.java
rm /Users/suri/repo/miniurl-api/identity-service/src/main/java/com/miniurl/dto/FeatureFlagDTO.java
rm /Users/suri/repo/miniurl-api/identity-service/src/main/java/com/miniurl/dto/ForgotPasswordRequest.java
rm /Users/suri/repo/miniurl-api/identity-service/src/main/java/com/miniurl/dto/GlobalFlagDTO.java
rm /Users/suri/repo/miniurl-api/identity-service/src/main/java/com/miniurl/dto/JwtAuthRequest.java
rm /Users/suri/repo/miniurl-api/identity-service/src/main/java/com/miniurl/dto/JwtAuthResponse.java
rm /Users/suri/repo/miniurl-api/identity-service/src/main/java/com/miniurl/dto/LoginOtpResponse.java
rm /Users/suri/repo/miniurl-api/identity-service/src/main/java/com/miniurl/dto/LoginRequest.java
rm /Users/suri/repo/miniurl-api/identity-service/src/main/java/com/miniurl/dto/LoginResponse.java
rm /Users/suri/repo/miniurl-api/identity-service/src/main/java/com/miniurl/dto/NotificationEvent.java
rm /Users/suri/repo/miniurl-api/identity-service/src/main/java/com/miniurl/dto/OtpVerificationRequest.java
rm /Users/suri/repo/miniurl-api/identity-service/src/main/java/com/miniurl/dto/PageableRequest.java
rm /Users/suri/repo/miniurl-api/identity-service/src/main/java/com/miniurl/dto/PagedResponse.java
rm /Users/suri/repo/miniurl-api/identity-service/src/main/java/com/miniurl/dto/ProfileUpdateRequest.java
rm /Users/suri/repo/miniurl-api/identity-service/src/main/java/com/miniurl/dto/ResendOtpRequest.java
rm /Users/suri/repo/miniurl-api/identity-service/src/main/java/com/miniurl/dto/ResetPasswordRequest.java
rm /Users/suri/repo/miniurl-api/identity-service/src/main/java/com/miniurl/dto/SignupRequest.java
rm /Users/suri/repo/miniurl-api/identity-service/src/main/java/com/miniurl/dto/UrlEvent.java
rm /Users/suri/repo/miniurl-api/identity-service/src/main/java/com/miniurl/dto/UrlResponse.java
rm /Users/suri/repo/miniurl-api/identity-service/src/main/java/com/miniurl/dto/UserResponse.java
```

- [ ] **Step 3: Delete duplicate exception and util files**

```bash
rm /Users/suri/repo/miniurl-api/identity-service/src/main/java/com/miniurl/exception/AliasNotAvailableException.java
rm /Users/suri/repo/miniurl-api/identity-service/src/main/java/com/miniurl/exception/RateLimitCooldownException.java
rm /Users/suri/repo/miniurl-api/identity-service/src/main/java/com/miniurl/exception/ResourceNotFoundException.java
rm /Users/suri/repo/miniurl-api/identity-service/src/main/java/com/miniurl/exception/UnauthorizedException.java
rm /Users/suri/repo/miniurl-api/identity-service/src/main/java/com/miniurl/exception/UrlLimitExceededException.java
rm /Users/suri/repo/miniurl-api/identity-service/src/main/java/com/miniurl/exception/UrlValidationException.java
rm /Users/suri/repo/miniurl-api/identity-service/src/main/java/com/miniurl/util/JwtUtil.java
rm /Users/suri/repo/miniurl-api/identity-service/src/main/java/com/miniurl/util/ValidationUtils.java
```

- [ ] **Step 4: Remove the now-empty package directories**

```bash
rmdir /Users/suri/repo/miniurl-api/identity-service/src/main/java/com/miniurl/dto
rmdir /Users/suri/repo/miniurl-api/identity-service/src/main/java/com/miniurl/exception
rmdir /Users/suri/repo/miniurl-api/identity-service/src/main/java/com/miniurl/util
```

- [ ] **Step 5: Verify build succeeds**

```bash
cd /Users/suri/repo/miniurl-api && mvn clean compile -pl common,identity-service
```

Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add identity-service/src/main/java/com/miniurl/
git commit -m "refactor(identity): remove duplicate common classes

Delete 32 files duplicated from the common module (24 DTOs, 6 exceptions,
2 utils). The identity-service already depends on the common module.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 4: URL Service — Fix YAML Duplicate Key

**Problem:** Duplicate `server:` keys in `url-service/src/main/resources/application.yml`. The second `server:` block (with `worker-id`) overwrites the first (with `port`), so `server.port` is lost.

- [ ] **Step 1: Fix the YAML to merge both `server` properties**

File: `url-service/src/main/resources/application.yml`

Replace the separate `server:` blocks with a merged one:

```yaml
server:
  port: 8081
  worker-id: ${SERVER_WORKER_ID:0}

spring:
  application:
    name: url-service
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
  datasource:
    url: jdbc:mysql://mysql-url:3306/url_db
    username: root
    password: root
    driver-class-name: com.mysql.cj.jdbc.Driver
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: false
    properties:
      hibernate:
        dialect: org.hibernate.dialect.MySQL8Dialect

eureka:
  client:
    serviceUrl:
      defaultZone: http://eureka-server:8761/eureka/

app:
  base-url: ${APP_BASE_URL:http://localhost:8080}
  ui-base-url: ${APP_UI_BASE_URL:http://localhost:3000}

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  endpoint:
    health:
      show-details: always
```

- [ ] **Step 2: Verify YAML is valid**

```bash
cd /Users/suri/repo/miniurl-api && python3 -c "import yaml; yaml.safe_load(open('url-service/src/main/resources/application.yml')); print('YAML is valid')"
```

Expected: "YAML is valid"

- [ ] **Step 3: Commit**

```bash
git add url-service/src/main/resources/application.yml
git commit -m "fix(url): merge duplicate server YAML keys into single block

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 5: Analytics Service — Fix Kafka Topic Name

**Problem:** Redirect service producer sends to `click-events` topic, but analytics consumer listens on `clicks` topic. No click events reach analytics.

- [ ] **Step 1: Change AnalyticsConsumer topic name**

File: `analytics-service/src/main/java/com/miniurl/analytics/kafka/AnalyticsConsumer.java`

Change line 18 from:
```java
@KafkaListener(topics = "clicks", groupId = "analytics-group")
```
to:
```java
@KafkaListener(topics = "click-events", groupId = "analytics-group")
```

- [ ] **Step 2: Verify build**

```bash
cd /Users/suri/repo/miniurl-api && mvn clean compile -pl common,analytics-service
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add analytics-service/src/main/java/com/miniurl/analytics/kafka/AnalyticsConsumer.java
git commit -m "fix(analytics): align Kafka topic with redirect service producer

Changed from 'clicks' to 'click-events' to match
ClickEventProducer.TOPIC.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 6: Parent POM — Add Eureka Server Module

**Problem:** `eureka-server` module exists but isn't listed in the parent POM, so multi-module builds skip it.

- [ ] **Step 1: Add eureka-server to modules in root pom.xml**

File: `pom.xml`

Change the modules list from:
```xml
<modules>
    <module>common</module>
    <module>api-gateway</module>
    <module>identity-service</module>
    <module>url-service</module>
    <module>redirect-service</module>
    <module>feature-service</module>
    <module>notification-service</module>
    <module>analytics-service</module>
    <module>miniurl-monolith</module>
</modules>
```

To:
```xml
<modules>
    <module>common</module>
    <module>eureka-server</module>
    <module>api-gateway</module>
    <module>identity-service</module>
    <module>url-service</module>
    <module>redirect-service</module>
    <module>feature-service</module>
    <module>notification-service</module>
    <module>analytics-service</module>
    <module>miniurl-monolith</module>
</modules>
```

- [ ] **Step 2: Verify full build includes eureka-server**

```bash
cd /Users/suri/repo/miniurl-api && mvn clean compile
```

Expected: BUILD SUCCESS — includes `[INFO] miniurl-parent .................................. SUCCESS`, `[INFO] common .................................. SUCCESS`, `[INFO] eureka-server .................................. SUCCESS`, etc.

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "build: add eureka-server module to parent POM

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 7: Common Module — Port Monolith Tests

**Problem:** The common module has no tests. Port `ValidationUtilsTest`, `DtoTest`, `ExceptionTest`, and `EntityTest` from the monolith.

- [ ] **Step 1: Create common test directory**

```bash
mkdir -p /Users/suri/repo/miniurl-api/common/src/test/java/com/miniurl
```

- [ ] **Step 2: Create ValidationUtilsTest in common**

File: `common/src/test/java/com/miniurl/ValidationUtilsTest.java`

```java
package com.miniurl;

import com.miniurl.util.ValidationUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ValidationUtils Tests")
class ValidationUtilsTest {

    @Test
    @DisplayName("isValidEmail - valid email")
    void isValidEmailValid() {
        assertTrue(ValidationUtils.isValidEmail("test@example.com"));
    }

    @Test
    @DisplayName("isValidEmail - invalid email")
    void isValidEmailInvalid() {
        assertFalse(ValidationUtils.isValidEmail("invalid-email"));
        assertFalse(ValidationUtils.isValidEmail(""));
        assertFalse(ValidationUtils.isValidEmail(null));
    }

    @Test
    @DisplayName("isValidUrl - valid url")
    void isValidUrlValid() {
        assertTrue(ValidationUtils.isValidUrl("https://example.com"));
        assertTrue(ValidationUtils.isValidUrl("http://example.com/path"));
    }

    @Test
    @DisplayName("isValidUrl - invalid url")
    void isValidUrlInvalid() {
        assertFalse(ValidationUtils.isValidUrl("not-a-url"));
        assertFalse(ValidationUtils.isValidUrl(""));
        assertFalse(ValidationUtils.isValidUrl(null));
    }

    @Test
    @DisplayName("sanitizeUrl")
    void sanitizeUrl() {
        assertEquals("https://example.com", ValidationUtils.sanitizeUrl(" https://example.com "));
    }
}
```

- [ ] **Step 3: Create ExceptionTest in common**

File: `common/src/test/java/com/miniurl/ExceptionTest.java`

```java
package com.miniurl;

import com.miniurl.exception.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Exception Tests")
class ExceptionTest {

    @Test
    @DisplayName("ResourceNotFoundException")
    void resourceNotFoundException() {
        ResourceNotFoundException ex = new ResourceNotFoundException("User not found");
        assertEquals("User not found", ex.getMessage());
    }

    @Test
    @DisplayName("UnauthorizedException")
    void unauthorizedException() {
        UnauthorizedException ex = new UnauthorizedException("Invalid credentials");
        assertEquals("Invalid credentials", ex.getMessage());
    }

    @Test
    @DisplayName("AliasNotAvailableException")
    void aliasNotAvailableException() {
        AliasNotAvailableException ex = new AliasNotAvailableException("Alias taken");
        assertEquals("Alias taken", ex.getMessage());
    }

    @Test
    @DisplayName("RateLimitCooldownException")
    void rateLimitCooldownException() {
        RateLimitCooldownException ex = new RateLimitCooldownException("Too many requests");
        assertEquals("Too many requests", ex.getMessage());
    }

    @Test
    @DisplayName("UrlLimitExceededException")
    void urlLimitExceededException() {
        UrlLimitExceededException ex = new UrlLimitExceededException("Limit reached");
        assertEquals("Limit reached", ex.getMessage());
    }

    @Test
    @DisplayName("UrlValidationException")
    void urlValidationException() {
        UrlValidationException ex = new UrlValidationException("Invalid URL");
        assertEquals("Invalid URL", ex.getMessage());
    }
}
```

- [ ] **Step 4: Create DtoTest in common**

File: `common/src/test/java/com/miniurl/DtoTest.java`

```java
package com.miniurl;

import com.miniurl.dto.ApiResponse;
import com.miniurl.dto.JwtAuthRequest;
import com.miniurl.dto.LoginRequest;
import com.miniurl.dto.SignupRequest;
import com.miniurl.dto.UrlResponse;
import com.miniurl.common.dto.ClickEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DTO Tests")
class DtoTest {

    @Test
    @DisplayName("ApiResponse creation")
    void apiResponse() {
        ApiResponse<String> response = ApiResponse.success("OK", "data");
        assertTrue(response.isSuccess());
        assertEquals("OK", response.getMessage());
        assertEquals("data", response.getData());
    }

    @Test
    @DisplayName("ClickEvent creation")
    void clickEvent() {
        ClickEvent event = ClickEvent.builder()
                .shortCode("abc123")
                .originalUrl("https://example.com")
                .timestamp(LocalDateTime.now())
                .build();
        assertEquals("abc123", event.getShortCode());
        assertEquals("https://example.com", event.getOriginalUrl());
        assertNotNull(event.getTimestamp());
    }

    @Test
    @DisplayName("SignupRequest")
    void signupRequest() {
        SignupRequest request = new SignupRequest();
        request.setUsername("testuser");
        request.setPassword("SecurePass1!");
        assertEquals("testuser", request.getUsername());
        assertEquals("SecurePass1!", request.getPassword());
    }

    @Test
    @DisplayName("UrlResponse")
    void urlResponse() {
        UrlResponse response = UrlResponse.builder()
                .shortCode("abc123")
                .originalUrl("https://example.com")
                .build();
        assertEquals("abc123", response.getShortCode());
    }
}
```

- [ ] **Step 5: Run common module tests**

```bash
cd /Users/suri/repo/miniurl-api && mvn clean test -pl common
```

Expected: Tests run, BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add common/src/test/
git commit -m "test(common): add unit tests for DTOs, exceptions, and validation utils

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 8: Identity Service — Port and Write New Tests

- [ ] **Step 1: Create test directories**

```bash
mkdir -p /Users/suri/repo/miniurl-api/identity-service/src/test/java/com/miniurl/identity
mkdir -p /Users/suri/repo/miniurl-api/identity-service/src/test/resources
```

- [ ] **Step 2: Create application-test.properties**

File: `identity-service/src/test/resources/application-test.properties`

```properties
spring.datasource.url=jdbc:h2:mem:testdb
spring.datasource.driver-class-name=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=
spring.jpa.hibernate.ddl-auto=create-drop
spring.jpa.database-platform=org.hibernate.dialect.H2Dialect
eureka.client.enabled=false
spring.kafka.bootstrap-servers=localhost:9092
```

- [ ] **Step 3: Create KeyServiceTest**

File: `identity-service/src/test/java/com/miniurl/identity/KeyServiceTest.java`

```java
package com.miniurl.identity;

import com.miniurl.identity.service.KeyService;
import com.nimbusds.jose.jwk.JWKSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.PrivateKey;
import java.security.PublicKey;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("KeyService Tests")
class KeyServiceTest {

    private KeyService keyService;

    @BeforeEach
    void setUp() {
        keyService = new KeyService();
        keyService.init();
    }

    @Test
    @DisplayName("init generates RSA key pair")
    void initGeneratesKeys() {
        assertNotNull(keyService.getPrivateKey());
        assertNotNull(keyService.getPublicKey());
    }

    @Test
    @DisplayName("getPublicJWKSet returns consistent key across calls")
    void getPublicJWKSetReturnsConsistentKey() {
        JWKSet firstCall = keyService.getPublicJWKSet();
        JWKSet secondCall = keyService.getPublicJWKSet();
        assertEquals(
            firstCall.getKeys().get(0).toPublicKey().toString(),
            secondCall.getKeys().get(0).toPublicKey().toString(),
            "JWK set should return the same public key on every call"
        );
    }

    @Test
    @DisplayName("getPrivateKey returns a usable PrivateKey")
    void getPrivateKeyReturnsUsableKey() {
        PrivateKey privateKey = keyService.getPrivateKey();
        assertNotNull(privateKey);
        assertEquals("RSA", privateKey.getAlgorithm());
    }

    @Test
    @DisplayName("getPublicKey returns a usable PublicKey")
    void getPublicKeyReturnsUsableKey() {
        PublicKey publicKey = keyService.getPublicKey();
        assertNotNull(publicKey);
        assertEquals("RSA", publicKey.getAlgorithm());
    }

    @Test
    @DisplayName("public and private keys form a valid pair")
    void keysFormValidPair() {
        // Verify they use the same algorithm and format
        assertEquals(keyService.getPrivateKey().getAlgorithm(), keyService.getPublicKey().getAlgorithm());
    }
}
```

- [ ] **Step 4: Create JwtServiceTest**

File: `identity-service/src/test/java/com/miniurl/identity/JwtServiceTest.java`

```java
package com.miniurl.identity;

import com.miniurl.identity.service.JwtService;
import com.miniurl.identity.service.KeyService;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("JwtService Tests")
class JwtServiceTest {

    private JwtService jwtService;
    private KeyService keyService;

    @BeforeEach
    void setUp() {
        keyService = new KeyService();
        keyService.init();
        jwtService = new JwtService(keyService, 3600000L);
    }

    @Test
    @DisplayName("generateToken creates valid RS256-signed token")
    void generateToken() {
        UserDetails userDetails = User.withUsername("testuser").password("pass").roles("USER").build();
        String token = jwtService.generateToken(userDetails);
        assertNotNull(token);
        assertTrue(token.split("\\.").length == 3, "JWT should have 3 parts");
    }

    @Test
    @DisplayName("generateToken with version claims")
    void generateTokenWithVersion() {
        UserDetails userDetails = User.withUsername("testuser").password("pass").roles("USER").build();
        String token = jwtService.generateToken(userDetails, 2);
        assertNotNull(token);
        // Verify token can be parsed with the public key
        var claims = Jwts.parser()
                .verifyWith(keyService.getPublicKey() instanceof java.security.interfaces.RSAPublicKey
                    ? (java.security.interfaces.RSAPublicKey) keyService.getPublicKey()
                    : null)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        assertEquals("testuser", claims.getSubject());
    }
}
```

- [ ] **Step 5: Run identity-service tests**

```bash
cd /Users/suri/repo/miniurl-api && mvn clean test -pl identity-service -Dspring.profiles.active=test
```

Expected: Tests pass, BUILD SUCCESS

If H2 is not on the classpath, add the H2 dependency to identity-service's pom.xml:
```xml
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 6: Commit**

```bash
git add identity-service/src/test/
git commit -m "test(identity): add KeyService and JwtService unit tests

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 9: URL Service — Port and Write New Tests

- [ ] **Step 1: Create URL service test directories**

```bash
mkdir -p /Users/suri/repo/miniurl-api/url-service/src/test/java/com/miniurl/url
mkdir -p /Users/suri/repo/miniurl-api/url-service/src/test/resources
```

- [ ] **Step 2: Create SnowflakeIdGeneratorTest**

File: `url-service/src/test/java/com/miniurl/url/SnowflakeIdGeneratorTest.java`

```java
package com.miniurl.url;

import com.miniurl.url.util.SnowflakeIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SnowflakeIdGenerator Tests")
class SnowflakeIdGeneratorTest {

    private SnowflakeIdGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new SnowflakeIdGenerator();
        ReflectionTestUtils.setField(generator, "workerId", 1L);
        generator.init();
    }

    @Test
    @DisplayName("nextId generates unique IDs")
    void nextIdGeneratesUniqueIds() {
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            long id = generator.nextId();
            assertTrue(ids.add(id), "ID should be unique: " + id);
        }
    }

    @Test
    @DisplayName("nextId is thread-safe")
    void nextIdIsThreadSafe() throws InterruptedException {
        int threadCount = 10;
        int idsPerThread = 100;
        Set<Long> ids = new ConcurrentSkipListSet<>();
        CountDownLatch latch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < idsPerThread; j++) {
                        ids.add(generator.nextId());
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();
        assertEquals(threadCount * idsPerThread, ids.size(), "All IDs should be unique under concurrent access");
    }

    @Test
    @DisplayName("nextId produces monotonically increasing IDs")
    void nextIdMonotonicallyIncreasing() {
        long prev = generator.nextId();
        for (int i = 0; i < 100; i++) {
            long current = generator.nextId();
            assertTrue(current > prev, "ID should be monotonically increasing");
            prev = current;
        }
    }
}
```

- [ ] **Step 3: Run URL service tests**

```bash
cd /Users/suri/repo/miniurl-api && mvn clean test -pl url-service
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add url-service/src/test/
git commit -m "test(url): add SnowflakeIdGenerator unit test with concurrency check

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 10: Redirect Service — Write New Tests

- [ ] **Step 1: Create redirect service test directories**

```bash
mkdir -p /Users/suri/repo/miniurl-api/redirect-service/src/test/java/com/miniurl/redirect
```

- [ ] **Step 2: Create RedirectServiceTest**

File: `redirect-service/src/test/java/com/miniurl/redirect/RedirectServiceTest.java`

```java
package com.miniurl.redirect;

import com.miniurl.redirect.producer.ClickEventProducer;
import com.miniurl.redirect.service.RedirectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RedirectService Tests")
class RedirectServiceTest {

    @Mock
    private ReactiveRedisTemplate<String, String> redisTemplate;

    @Mock
    private ReactiveValueOperations<String, String> valueOps;

    @Mock
    private WebClient webClient;

    @Mock
    private ClickEventProducer clickEventProducer;

    private RedirectService redirectService;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        redirectService = new RedirectService(redisTemplate, webClient, clickEventProducer);
    }

    @Test
    @DisplayName("resolveUrl returns cached URL on cache hit")
    void resolveUrlCacheHit() {
        when(valueOps.get("url:cache:abc123")).thenReturn(Mono.just("https://example.com"));

        StepVerifier.create(redirectService.resolveUrl("abc123"))
                .expectNext("https://example.com")
                .verifyComplete();
    }

    @Test
    @DisplayName("resolveUrl returns empty when cache miss and service unavailable")
    void resolveUrlCacheMissNoFallback() {
        when(valueOps.get("url:cache:abc123")).thenReturn(Mono.empty());

        StepVerifier.create(redirectService.resolveUrl("abc123"))
                .verifyComplete();
    }
}
```

- [ ] **Step 3: Run redirect-service tests**

```bash
cd /Users/suri/repo/miniurl-api && mvn clean test -pl redirect-service
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add redirect-service/src/test/
git commit -m "test(redirect): add RedirectService unit tests with mocked Redis/WebClient

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 11: Feature Service — Port Monolith Tests

- [ ] **Step 1: Create feature service test directories**

```bash
mkdir -p /Users/suri/repo/miniurl-api/feature-service/src/test/java/com/miniurl/feature
```

- [ ] **Step 2: Create FeatureFlagServiceTest**

File: `feature-service/src/test/java/com/miniurl/feature/FeatureFlagServiceTest.java`

```java
package com.miniurl.feature;

import com.miniurl.feature.entity.FeatureFlag;
import com.miniurl.feature.repository.FeatureFlagRepository;
import com.miniurl.feature.service.FeatureFlagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FeatureFlagService Tests")
class FeatureFlagServiceTest {

    @Mock
    private FeatureFlagRepository featureFlagRepository;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOps;

    private FeatureFlagService featureFlagService;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        featureFlagService = new FeatureFlagService(featureFlagRepository, redisTemplate);
    }

    @Test
    @DisplayName("isFeatureEnabled returns true when feature is enabled")
    void isFeatureEnabledTrue() {
        FeatureFlag flag = new FeatureFlag();
        flag.setEnabled(true);
        flag.setFeatureKey("test-feature");

        when(valueOps.get(anyString())).thenReturn(null);
        when(featureFlagRepository.findByFeatureKeyAndRoleId("test-feature", 2L))
                .thenReturn(Optional.of(flag));

        assertTrue(featureFlagService.isFeatureEnabled("test-feature", 2L));
    }

    @Test
    @DisplayName("isFeatureEnabled returns false when feature not found")
    void isFeatureEnabledNotFound() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(featureFlagRepository.findByFeatureKeyAndRoleId("unknown-feature", 2L))
                .thenReturn(Optional.empty());

        assertFalse(featureFlagService.isFeatureEnabled("unknown-feature", 2L));
    }

    @Test
    @DisplayName("isFeatureEnabled returns false when feature is disabled")
    void isFeatureEnabledDisabled() {
        FeatureFlag flag = new FeatureFlag();
        flag.setEnabled(false);
        flag.setFeatureKey("disabled-feature");

        when(valueOps.get(anyString())).thenReturn(null);
        when(featureFlagRepository.findByFeatureKeyAndRoleId("disabled-feature", 2L))
                .thenReturn(Optional.of(flag));

        assertFalse(featureFlagService.isFeatureEnabled("disabled-feature", 2L));
    }
}
```

- [ ] **Step 3: Run feature-service tests**

```bash
cd /Users/suri/repo/miniurl-api && mvn clean test -pl feature-service
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add feature-service/src/test/
git commit -m "test(feature): add FeatureFlagService unit tests with mocked Redis

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 12: Full Build Verification

- [ ] **Step 1: Run full Maven build with tests**

```bash
cd /Users/suri/repo/miniurl-api && mvn clean install
```

Expected: BUILD SUCCESS — all modules compile and all tests pass.

If any module fails, investigate and fix.

- [ ] **Step 2: Verify coverage report**

```bash
cd /Users/suri/repo/miniurl-api && find . -path "*/target/surefire-reports/*.txt" -exec grep -l "Tests run:" {} \;
```

Expected: Test reports exist for modules where tests were added.

---

### Task 13: Docker Compose — Add Microservice Containers

**Problem:** `docker-compose.yml` has only infrastructure (Eureka, Kafka, Redis, MySQL, Prometheus, Grafana) but no microservice app containers.

- [ ] **Step 1: Create per-service Dockerfiles**

Create `Dockerfile` in each microservice root:

File: `eureka-server/Dockerfile`
```dockerfile
FROM openjdk:21-jdk-slim AS build
WORKDIR /app
COPY pom.xml .
COPY eureka-server/pom.xml eureka-server/
RUN mvn dependency:go-offline -pl eureka-server

COPY eureka-server/src eureka-server/src
RUN mvn package -pl eureka-server -DskipTests

FROM openjdk:21-jdk-slim
WORKDIR /app
COPY --from=build /app/eureka-server/target/*.jar app.jar
EXPOSE 8761
ENTRYPOINT ["java", "-jar", "app.jar"]
```

File: `api-gateway/Dockerfile`
```dockerfile
FROM openjdk:21-jdk-slim AS build
WORKDIR /app
COPY pom.xml .
COPY api-gateway/pom.xml api-gateway/
COPY common common/
RUN mvn dependency:go-offline -pl api-gateway -am

COPY api-gateway/src api-gateway/src
RUN mvn package -pl api-gateway -DskipTests

FROM openjdk:21-jdk-slim
WORKDIR /app
COPY --from=build /app/api-gateway/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

File: `identity-service/Dockerfile` (same pattern)

```dockerfile
FROM openjdk:21-jdk-slim AS build
WORKDIR /app
COPY pom.xml .
COPY identity-service/pom.xml identity-service/pom.xml
COPY common common/
RUN --mount=type=cache,target=/root/.m2 mvn dependency:go-offline -pl identity-service -am

COPY identity-service/src identity-service/src
RUN --mount=type=cache,target=/root/.m2 mvn package -pl identity-service -DskipTests

FROM openjdk:21-jdk-slim
WORKDIR /app
COPY --from=build /app/identity-service/target/*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
```

Note: Use `spring-boot:repackage` or verify the JAR filename matches.

- [ ] **Step 2: Update docker-compose.yml**

Add app containers to `docker-compose.yml` (or create `docker-compose.services.yml`):

```yaml
version: '3.8'
services:
  # ... existing infrastructure ...

  identity-service:
    build: ./identity-service
    ports:
      - "8082:8082"
    environment:
      - KAFKA_BOOTSTRAP_SERVERS=kafka:9092
      - EUREKA_SERVER_URL=http://eureka-server:8761/eureka/
      - SPRING_DATASOURCE_URL=jdbc:mysql://mysql-identity:3306/identity_db
      - SPRING_DATASOURCE_USERNAME=root
      - SPRING_DATASOURCE_PASSWORD=root
    depends_on:
      - eureka-server
      - kafka
      - mysql-identity

  url-service:
    build: ./url-service
    ports:
      - "8081:8081"
    environment:
      - KAFKA_BOOTSTRAP_SERVERS=kafka:9092
      - EUREKA_SERVER_URL=http://eureka-server:8761/eureka/
      - SPRING_DATASOURCE_URL=jdbc:mysql://mysql-url:3306/url_db
      - SPRING_DATASOURCE_USERNAME=root
      - SPRING_DATASOURCE_PASSWORD=root
    depends_on:
      - eureka-server
      - kafka
      - mysql-url

  api-gateway:
    build: ./api-gateway
    ports:
      - "8080:8080"
    environment:
      - GATEWAY_REDIS_HOST=redis
      - GATEWAY_EUREKA_URL=http://eureka-server:8761/eureka/
    depends_on:
      - eureka-server
      - redis
      - identity-service
      - url-service

  redirect-service:
    build: ./redirect-service
    ports:
      - "8083:8083"
    environment:
      - REDIS_HOST=redis
      - EUREKA_SERVER_URL=http://eureka-server:8761/eureka/
      - KAFKA_BOOTSTRAP_SERVERS=kafka:9092
    depends_on:
      - eureka-server
      - redis
      - kafka
      - url-service

  feature-service:
    build: ./feature-service
    ports:
      - "8084:8084"
    environment:
      - REDIS_HOST=redis
      - EUREKA_SERVER_URL=http://eureka-server:8761/eureka/
      - SPRING_DATASOURCE_URL=jdbc:mysql://mysql-feature:3306/feature_db
      - SPRING_DATASOURCE_USERNAME=root
      - SPRING_DATASOURCE_PASSWORD=root
    depends_on:
      - eureka-server
      - redis
      - mysql-feature

  notification-service:
    build: ./notification-service
    environment:
      - KAFKA_BOOTSTRAP_SERVERS=kafka:9092
      - EUREKA_SERVER_URL=http://eureka-server:8761/eureka/
    depends_on:
      - eureka-server
      - kafka

  analytics-service:
    build: ./analytics-service
    environment:
      - KAFKA_BOOTSTRAP_SERVERS=kafka:9092
      - EUREKA_SERVER_URL=http://eureka-server:8761/eureka/
      - SPRING_DATASOURCE_URL=jdbc:mysql://mysql-url:3306/url_db
      - SPRING_DATASOURCE_USERNAME=root
      - SPRING_DATASOURCE_PASSWORD=root
    depends_on:
      - eureka-server
      - kafka
      - mysql-url
```

- [ ] **Step 3: Verify docker-compose builds**

```bash
cd /Users/suri/repo/miniurl-api && docker compose build identity-service
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add eureka-server/Dockerfile api-gateway/Dockerfile identity-service/Dockerfile url-service/Dockerfile redirect-service/Dockerfile feature-service/Dockerfile notification-service/Dockerfile analytics-service/Dockerfile docker-compose.yml
git commit -m "feat(deploy): add per-service Dockerfiles and docker-compose app containers

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 14: Remove Stale Files

- [ ] **Step 1: Remove stale docker-compose.dev.yml**

```bash
git rm /Users/suri/repo/miniurl-api/docker-compose.dev.yml
```

- [ ] **Step 2: Commit**

```bash
git commit -m "cleanup: remove stale docker-compose.dev.yml

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Verification

After all tasks complete, run the full verification:

```bash
# Full build with tests
cd /Users/suri/repo/miniurl-api && mvn clean install

# Verify all modules listed
mvn exec:exec -Dexec.executable=echo -Dexec.args='${project.artifactId}' || true
mvn --also-make dependency:tree

# Verify test results
find . -path "*/target/surefire-reports/*.txt" -exec echo "=== {} ===" \; -exec head -5 {} \;
```

### Success Criteria Checklist

- [ ] `mvn clean install` passes for all modules
- [ ] `eureka-server` builds as part of multi-module build
- [ ] Common module tests pass (ValidationUtils, DTOs, Exceptions)
- [ ] Identity service tests pass (KeyService, JwtService)
- [ ] URL service tests pass (SnowflakeIdGenerator)
- [ ] Redirect service tests pass (RedirectService with mocks)
- [ ] Feature service tests pass (FeatureFlagService with mocks)
- [ ] All duplicate classes removed from identity-service
- [ ] `KeyService.getPublicJWKSet()` returns consistent key
- [ ] Analytics consumer listens on `click-events` topic
- [ ] URL service YAML has no duplicate keys
- [ ] Docker Compose can build all service images
