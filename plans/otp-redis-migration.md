# Plan: Move OTP Storage from Database to Redis Cache

## Status: ✅ IMPLEMENTED

All changes have been implemented. See the [Files Changed Summary](#files-changed-summary) below for the complete list with implementation status.

---

## Summary

Replace the OTP fields currently stored on the `users` table (`otp_code`, `otp_expiry`, `otp_verified`, `last_otp_sent_at`) with Redis cache entries having a **5-minute TTL**. The cooldown tracking (`last_otp_sent_at`) and email verification (`otp_verified`) also move to Redis with their own TTLs.

---

## Current State

### OTP-related fields on [`User`](identity-service/src/main/java/com/miniurl/identity/entity/User.java:47) entity:
| Field | DB Column | Purpose |
|---|---|---|
| `otpCode` | `otp_code` | 6-digit OTP |
| `otpExpiry` | `otp_expiry` | Expiration timestamp |
| `otpVerified` | `otp_verified` | Whether OTP was verified |
| `lastOtpSentAt` | `last_otp_sent_at` | Cooldown tracking (30s resend) |

### Methods in [`AuthService`](identity-service/src/main/java/com/miniurl/identity/service/AuthService.java) that touch OTP:
- [`sendLoginOtp(User user)`](identity-service/src/main/java/com/miniurl/identity/service/AuthService.java:525) — checks cooldown, reuses or generates OTP, saves to user entity
- [`generateAndSendLoginOtp(User user)`](identity-service/src/main/java/com/miniurl/identity/service/AuthService.java:569) — generates OTP, sets on user, saves user
- [`resendLoginOtp(String usernameOrEmail)`](identity-service/src/main/java/com/miniurl/identity/service/AuthService.java:601) — finds user, checks cooldown, resends or regenerates
- [`verifyLoginOtp(String usernameOrEmail, String otp)`](identity-service/src/main/java/com/miniurl/identity/service/AuthService.java:651) — validates OTP from user entity, clears fields

### Endpoints in [`AuthController`](identity-service/src/main/java/com/miniurl/identity/controller/AuthController.java):
- `POST /api/auth/login` — authenticates password, sends OTP
- `POST /api/auth/verify-otp` — verifies OTP, returns JWT
- `POST /api/auth/resend-otp` — resends OTP

### Redis already exists in the project:
- [`feature-service`](feature-service/src/main/resources/application.yml:19) uses `spring.data.redis` with `RedisTemplate<String, Object>`
- [`redirect-service`](redirect-service/src/main/java/com/miniurl/redirect/service/RedirectService.java) uses reactive Redis for URL caching
- Redis is deployed as part of the infrastructure

---

## Proposed Changes

### 1. Add Redis Dependency ✅

**File:** [`identity-service/pom.xml`](identity-service/pom.xml)

Add `spring-boot-starter-data-redis` dependency:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

No additional client dependency needed — Spring Boot auto-configures Lettuce by default.

---

### 2. Add Redis Configuration to application YAML Files ✅

**File:** [`identity-service/src/main/resources/application.yml`](identity-service/src/main/resources/application.yml)

Add:
```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
```

**File:** [`identity-service/src/main/resources/application-dev.yml`](identity-service/src/main/resources/application-dev.yml)

No Redis-specific dev overrides needed (defaults to localhost:6379).

**File:** [`identity-service/src/main/resources/application-prod.yml`](identity-service/src/main/resources/application-prod.yml)

No Redis-specific prod overrides needed (set via environment variables).

---

### 3. Create Redis Configuration Class ✅

**New File:** `identity-service/src/main/java/com/miniurl/identity/config/RedisConfig.java`

```java
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        return template;
    }
}
```

We use `StringRedisSerializer` for both keys and values since OTP data is simple strings/timestamps. This is simpler than the `RedisTemplate<String, Object>` used in feature-service and avoids serialization issues.

---

### 4. Create OTP Service ✅

**New File:** `identity-service/src/main/java/com/miniurl/identity/service/OtpService.java`

This service encapsulates all Redis OTP operations:

```java
@Service
public class OtpService {

    private static final String OTP_KEY_PREFIX = "otp:";
    private static final String OTP_COOLDOWN_PREFIX = "otp:cooldown:";
    private static final String EMAIL_VERIFIED_PREFIX = "email_verified:";

    private final RedisTemplate<String, String> redisTemplate;

    @Value("${app.otp.expiry-minutes:5}")
    private int otpExpiryMinutes;

    @Value("${app.otp.resend-cooldown-seconds:30}")
    private int otpResendCooldownSeconds;

    // Constructor injection

    /**
     * Store OTP for a user with configurable TTL (default 5 minutes).
     */
    public void storeOtp(Long userId, String otp) {
        String key = OTP_KEY_PREFIX + userId;
        redisTemplate.opsForValue().set(key, otp, otpExpiryMinutes, TimeUnit.MINUTES);
    }

    /**
     * Retrieve stored OTP for a user.
     * @return OTP string or null if expired/not found
     */
    public String getOtp(Long userId) {
        return redisTemplate.opsForValue().get(OTP_KEY_PREFIX + userId);
    }

    /**
     * Delete OTP after successful verification.
     */
    public void deleteOtp(Long userId) {
        redisTemplate.delete(OTP_KEY_PREFIX + userId);
    }

    /**
     * Check if OTP exists (not expired) for a user.
     */
    public boolean hasValidOtp(Long userId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(OTP_KEY_PREFIX + userId));
    }

    /**
     * Set cooldown marker with configurable TTL (default 30 seconds).
     * @return true if cooldown was set (no existing cooldown), false if still in cooldown
     */
    public boolean trySetCooldown(Long userId) {
        String key = OTP_COOLDOWN_PREFIX + userId;
        // SETNX with TTL — atomic "set if not exists"
        return Boolean.TRUE.equals(
            redisTemplate.opsForValue().setIfAbsent(key, "1", otpResendCooldownSeconds, TimeUnit.SECONDS)
        );
    }

    /**
     * Get remaining cooldown seconds for a user.
     * @return remaining seconds, or 0 if no cooldown
     */
    public long getCooldownRemainingSeconds(Long userId) {
        Long ttl = redisTemplate.getExpire(OTP_COOLDOWN_PREFIX + userId, TimeUnit.SECONDS);
        return ttl != null && ttl > 0 ? ttl : 0;
    }

    // ==================== Email Verification ====================

    /**
     * Mark a user's email as verified in Redis.
     * No TTL — persists until explicitly deleted or Redis is flushed.
     */
    public void markEmailVerified(Long userId) {
        String key = EMAIL_VERIFIED_PREFIX + userId;
        redisTemplate.opsForValue().set(key, "1");
    }

    /**
     * Check if a user's email has been verified.
     */
    public boolean isEmailVerified(Long userId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(EMAIL_VERIFIED_PREFIX + userId));
    }

    /**
     * Remove email verified marker (e.g., on account deletion).
     */
    public void deleteEmailVerified(Long userId) {
        redisTemplate.delete(EMAIL_VERIFIED_PREFIX + userId);
    }
}
```

**Key design decisions:**
- **Configurable TTL** via `@Value` — defaults to 5 minutes for OTP, 30 seconds for cooldown; overridable via `application.yml` or environment variables
- **Redis auto-expiry** — no manual expiry checking needed; Redis drops keys automatically
- **`SETNX` for cooldown** — atomic, no race conditions
- **Key format:** `otp:{userId}` for OTP, `otp:cooldown:{userId}` for cooldown, `email_verified:{userId}` for email verification
- **Email verification** uses a persistent Redis key (no TTL) — survives Redis restarts only if Redis has persistence enabled

---

### 5. Modify AuthService ✅

**File:** [`identity-service/src/main/java/com/miniurl/identity/service/AuthService.java`](identity-service/src/main/java/com/miniurl/identity/service/AuthService.java)

Changes:
- Inject `OtpService` instead of relying on `User` entity OTP fields
- Remove `@Value("${app.otp.expiry-minutes:10}")` and `@Value("${app.otp.resend-cooldown-seconds:30}")` — these move to `OtpService` with defaults of 5 and 30 respectively
- Rewrite OTP methods:

| Method | Change |
|---|---|
| `sendLoginOtp(User user)` | Use `otpService.trySetCooldown(userId)` instead of checking `user.getLastOtpSentAt()`. Use `otpService.hasValidOtp(userId)` instead of checking `user.getOtpCode()/getOtpExpiry()`. No longer saves user entity. |
| `generateAndSendLoginOtp(User user)` | Call `otpService.storeOtp(userId, otp)` instead of setting fields on user. No longer saves user entity. |
| `resendLoginOtp(String usernameOrEmail)` | Use `otpService` for cooldown check and OTP retrieval. No longer saves user entity. |
| `verifyLoginOtp(String usernameOrEmail, String otp)` | Use `otpService.getOtp(userId)` to retrieve and compare. Call `otpService.deleteOtp(userId)` on success instead of clearing fields. No longer saves user entity. |
| `registerUser(...)` | Use `otpService.markEmailVerified(user.getId())` instead of setting `otpVerified` on User entity. |

**Important:** The `@Transactional` annotation has been **removed** from OTP methods since they no longer touch the database. This is a performance win.

---

### 6. Modify AuthController ✅

**File:** [`identity-service/src/main/java/com/miniurl/identity/controller/AuthController.java`](identity-service/src/main/java/com/miniurl/identity/controller/AuthController.java)

No changes needed:
- [`login()`](identity-service/src/main/java/com/miniurl/identity/controller/AuthController.java:96) — unchanged (still calls `authService.sendLoginOtp(user)`)
- [`verifyOtp()`](identity-service/src/main/java/com/miniurl/identity/controller/AuthController.java:131) — unchanged (still calls `authService.verifyLoginOtp()`)
- [`resendOtp()`](identity-service/src/main/java/com/miniurl/identity/controller/AuthController.java:161) — unchanged (still calls `authService.resendLoginOtp()`)

The controller methods that look up the user for lockout checks (`login`, `verifyOtp`, `resendOtp`) still need `userRepository` — that's fine, lockout state remains on the User entity.

---

### 7. Remove OTP Fields from User Entity ✅

**File:** [`identity-service/src/main/java/com/miniurl/identity/entity/User.java`](identity-service/src/main/java/com/miniurl/identity/entity/User.java)

Removed:
- Field `otpCode` (line 47-49)
- Field `otpExpiry` (line 51-52)
- Field `otpVerified` (line 54-55)
- Field `lastOtpSentAt` (line 82-83)
- Corresponding getters/setters
- Builder fields and methods for these fields

---

### 8. Update Database Init Script ✅

**File:** [`scripts/init-identity-db.sql`](scripts/init-identity-db.sql)

Removed the OTP columns from the `users` table definition:
- Removed `otp_code VARCHAR(6),`
- Removed `otp_expiry DATETIME,`
- Removed `otp_verified BOOLEAN DEFAULT FALSE,`
- Removed `last_otp_sent_at DATETIME,`

Updated the default admin user INSERT to remove `otp_verified` from the column list and the value `true` from the SELECT.

**File:** [`scripts/init-db.sql`](scripts/init-db.sql)

Same changes applied to the monolith init script.

Hibernate's `ddl-auto: update` will handle dropping the columns from existing databases in dev environments.

---

### 9. Update Tests ✅

**Files reviewed — no changes needed:**

All 8 existing test files were reviewed and confirmed clean — none reference OTP fields (`otpCode`, `otpExpiry`, `otpVerified`, `lastOtpSentAt`, `isOtpVerified`, `setOtpVerified`, `.otpVerified()`):

| Test File | Status |
|---|---|
| [`AuthControllerLockoutTest.java`](identity-service/src/test/java/com/miniurl/identity/AuthControllerLockoutTest.java) | ✅ Clean — no OTP field references |
| [`AuthControllerDeleteAccountTest.java`](identity-service/src/test/java/com/miniurl/identity/AuthControllerDeleteAccountTest.java) | ✅ Clean — no OTP field references |
| [`UserLockoutTest.java`](identity-service/src/test/java/com/miniurl/identity/UserLockoutTest.java) | ✅ Clean — no OTP field references |
| [`ProfileControllerTest.java`](identity-service/src/test/java/com/miniurl/identity/ProfileControllerTest.java) | ✅ Clean — no OTP field references |
| [`SettingsControllerTest.java`](identity-service/src/test/java/com/miniurl/identity/SettingsControllerTest.java) | ✅ Clean — no OTP field references |
| [`SelfInviteControllerTest.java`](identity-service/src/test/java/com/miniurl/identity/SelfInviteControllerTest.java) | ✅ Clean — no OTP field references |
| [`FeatureFlagPublicControllerTest.java`](identity-service/src/test/java/com/miniurl/identity/FeatureFlagPublicControllerTest.java) | ✅ Clean — no OTP field references |
| [`AdminControllerTest.java`](identity-service/src/test/java/com/miniurl/identity/AdminControllerTest.java) | ✅ Clean — no OTP field references |

**New test file created:**

| Test File | Status |
|---|---|
| [`OtpServiceTest.java`](identity-service/src/test/java/com/miniurl/identity/OtpServiceTest.java) | ✅ Created — 14 unit tests covering storeOtp, getOtp, deleteOtp, hasValidOtp, trySetCooldown, getCooldownRemainingSeconds, markEmailVerified, isEmailVerified, deleteEmailVerified |

---

### 10. Helm Chart Updates ✅

**File:** [`helm/miniurl/templates/configmap.yaml`](helm/miniurl/templates/configmap.yaml)

Reviewed — Redis host/port environment variables are already passed to all services via the global configmap. No changes needed.

---

## Files Changed Summary

| Action | File | Status |
|---|---|---|
| **ADD** | `identity-service/src/main/java/com/miniurl/identity/config/RedisConfig.java` | ✅ Done |
| **ADD** | `identity-service/src/main/java/com/miniurl/identity/service/OtpService.java` | ✅ Done |
| **ADD** | `identity-service/src/test/java/com/miniurl/identity/OtpServiceTest.java` | ✅ Done |
| **MODIFY** | `identity-service/pom.xml` — add `spring-boot-starter-data-redis` | ✅ Done |
| **MODIFY** | `identity-service/src/main/resources/application.yml` — add Redis config + OTP properties | ✅ Done |
| **MODIFY** | `identity-service/src/main/java/com/miniurl/identity/service/AuthService.java` — rewrite OTP methods | ✅ Done |
| **MODIFY** | `identity-service/src/main/java/com/miniurl/identity/entity/User.java` — remove OTP fields | ✅ Done |
| **MODIFY** | `identity-service/src/main/java/com/miniurl/identity/repository/UserRepository.java` — remove `findByEmailAndOtpVerified` | ✅ Done |
| **MODIFY** | `scripts/init-identity-db.sql` — remove OTP columns from users table | ✅ Done |
| **MODIFY** | `scripts/init-db.sql` — remove OTP columns from users table (monolith) | ✅ Done |
| **NO CHANGE** | All 8 existing test files — confirmed clean | ✅ Verified |
| **NO CHANGE** | `identity-service/src/main/java/com/miniurl/identity/controller/AuthController.java` | ✅ Verified |
| **NO CHANGE** | Helm chart configmap — Redis env vars already present | ✅ Verified |

---

## Risks & Mitigations

| Risk | Mitigation |
|---|---|
| Redis unavailable → login breaks | OTP is only for 2FA step; password auth still works. Redis is already a critical dependency (redirect-service, feature-service). Add health check. |
| OTP TTL reduced from 10min to 5min | Acceptable — 5 minutes is standard for OTP. Faster expiry = better security. |
| Cooldown tracking moves from DB to Redis | Redis `SETNX` with TTL is more reliable than DB timestamp comparison. No clock skew issues. |
| No `@Transactional` on OTP methods | Safe — Redis operations are atomic. No DB writes happen in these methods anymore. |
| Email verification marker in Redis (no TTL) | If Redis is flushed, verified users would need re-verification. Acceptable for now; can add DB backfill later if needed. |
