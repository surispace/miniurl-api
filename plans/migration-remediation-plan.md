# MiniURL Migration Remediation Plan: NO-GO → GO

**Date:** 2026-04-30
**Based on:** [`migration-audit-report.md`](migration-audit-report.md)
**Target:** Move 15 P0 blockers to resolved, achieving production-readiness
**Principle:** Fix one at a time, never break existing microservices

---

## Table of Contents

1. [Phase 1: Security Blockers](#phase-1-security-blockers)
2. [Phase 2: Missing Controller/API Parity](#phase-2-missing-controllerapi-parity)
3. [Phase 3: Event/Cache Consistency](#phase-3-eventcache-consistency)
4. [Phase 4: RSA/JWT Production Readiness](#phase-4-rsajwt-production-readiness)
5. [Phase 5: Integration Test Parity](#phase-5-integration-test-parity)
6. [Phase 6: Final Production-Readiness Validation](#phase-6-final-production-readiness-validation)
7. [P0 Fix Checklist](#p0-fix-checklist)
8. [Regression Test Checklist](#regression-test-checklist)
9. [Branch/Commit Breakdown](#branchcommit-breakdown)
10. [Final GO/NO-GO Gate](#final-gono-go-gate)

---

## Phase 1: Security Blockers

**Goal:** Eliminate all security vulnerabilities before any other work.
**Services affected:** redirect-service, identity-service
**Risk of not fixing:** XSS attacks, account takeover, data breaches

---

### P0-1: Open Redirect Vulnerability — No URL Validation in Redirect Controller

**Root Cause:**
The monolith's [`RedirectController.isValidRedirectUrl()`](miniurl-monolith/src/main/java/com/miniurl/controller/RedirectController.java:29) validates the redirect target before issuing a 302, blocking `javascript:`, `data:`, `vbscript:`, `file:`, `ftp:`, and `about:` protocols. The microservices [`RedirectController`](redirect-service/src/main/java/com/miniurl/redirect/controller/RedirectController.java:29) has no such validation. An attacker who creates a URL record with a `javascript:` target can execute XSS via the redirect endpoint.

**Exact Files Requiring Changes:**

| File | Change Type |
|---|---|
| [`RedirectController.java`](redirect-service/src/main/java/com/miniurl/redirect/controller/RedirectController.java:29) | Add URL validation before 302 |
| [`RedirectService.java`](redirect-service/src/main/java/com/miniurl/redirect/service/RedirectService.java:36) | Optionally add validation in service layer |
| [`RedirectServiceTest.java`](redirect-service/src/test/java/com/miniurl/redirect/RedirectServiceTest.java:1) | Add security test cases |

**Required Code Changes:**

In [`RedirectController.java`](redirect-service/src/main/java/com/miniurl/redirect/controller/RedirectController.java:39), add validation after resolving the URL and before issuing the redirect:

```java
// After: .flatMap(originalUrl -> {
// Add:
if (!isValidRedirectUrl(originalUrl)) {
    log.warn("Blocked redirect to unsafe URL: {}", originalUrl);
    return ServerResponse.status(400).bodyValue(
        Map.of("success", false, "message", "Invalid redirect URL"));
}
// Then proceed with 302 redirect
```

Add the validation method (ported from monolith):

```java
private static final Set<String> BLOCKED_PROTOCOLS = 
    Set.of("javascript:", "data:", "vbscript:", "file:", "ftp:", "about:");

private boolean isValidRedirectUrl(String url) {
    if (url == null || url.isBlank()) return false;
    String lower = url.toLowerCase().trim();
    for (String protocol : BLOCKED_PROTOCOLS) {
        if (lower.startsWith(protocol)) return false;
    }
    return lower.startsWith("http://") || lower.startsWith("https://");
}
```

**Required Tests:**

| Test | What It Verifies |
|---|---|
| `shouldBlockJavascriptProtocol()` | `javascript:alert(1)` returns 400 |
| `shouldBlockDataProtocol()` | `data:text/html,<script>alert(1)</script>` returns 400 |
| `shouldBlockVbscriptProtocol()` | `vbscript:msgbox(1)` returns 400 |
| `shouldBlockFileProtocol()` | `file:///etc/passwd` returns 400 |
| `shouldBlockFtpProtocol()` | `ftp://evil.com/malware.exe` returns 400 |
| `shouldAllowHttp()` | `http://example.com` returns 302 |
| `shouldAllowHttps()` | `https://example.com` returns 302 |
| `shouldBlockNullUrl()` | null URL returns 400 |
| `shouldBlockEmptyUrl()` | empty URL returns 400 |

**Acceptance Criteria:**
- [ ] All 9 test cases pass
- [ ] Existing redirect tests still pass (no regression)
- [ ] `curl -v "http://localhost:8080/r/VALIDCODE"` for a URL with `javascript:` target returns 400, not 302

**Verification Command:**
```bash
# Create a URL with javascript: target via URL service, then attempt redirect
curl -v -o /dev/null "http://localhost:8080/r/EVILCODE" 2>&1 | grep "< HTTP"
# Expected: HTTP/1.1 400 Bad Request
```

**Estimated Risk if Fixed Incorrectly:**
- **Low.** The validation is additive (only blocks, never allows). Worst case: false positive blocks a legitimate `http:` URL due to regex bug — easily caught by tests.
- **Mitigation:** Use exact string matching (`startsWith`) rather than regex to avoid ReDoS.

**Implementation Order:** 1st (highest priority — active security vulnerability)

---

### P0-2: Account Lockout Not Enforced at Login

**Root Cause:**
The [`User`](identity-service/src/main/java/com/miniurl/identity/entity/User.java:211) entity has `incrementFailedLoginAttempts()`, `resetFailedLoginAttempts()`, `isAccountLocked()`, `isLockoutExpired()`, and `isLoginAttemptAllowed()` methods — identical to the monolith. However, the [`AuthController.login()`](identity-service/src/main/java/com/miniurl/identity/controller/AuthController.java:89) endpoint never calls them. The monolith's [`AuthService.authenticateUser()`](miniurl-monolith/src/main/java/com/miniurl/service/AuthService.java:469) checks lockout before attempting password validation.

**Exact Files Requiring Changes:**

| File | Change Type |
|---|---|
| [`AuthService.java`](identity-service/src/main/java/com/miniurl/identity/service/AuthService.java:515) | Add lockout check in login flow |
| [`AuthController.java`](identity-service/src/main/java/com/miniurl/identity/controller/AuthController.java:89) | No changes needed (delegates to service) |
| [`User.java`](identity-service/src/main/java/com/miniurl/identity/entity/User.java:211) | No changes needed (logic already exists) |

**Required Code Changes:**

In [`AuthService.java`](identity-service/src/main/java/com/miniurl/identity/service/AuthService.java:515), modify the `login()` method. Before the password check, add:

```java
public AuthResponse login(LoginRequest request) {
    User user = userRepository.findByUsername(request.getUsername())
        .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));

    // ADD: Check account lockout BEFORE password validation
    if (!user.isLoginAttemptAllowed()) {
        long remainingLockoutSeconds = user.getRemainingLockoutSeconds();
        log.warn("Account locked for user: {}. Remaining: {}s", 
            user.getUsername(), remainingLockoutSeconds);
        throw new AccountLockedException(
            "Account is temporarily locked. Please try again in " 
            + remainingLockoutSeconds + " seconds.");
    }

    // Existing password check...
    if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
        // ADD: Increment failed attempts on wrong password
        user.incrementFailedLoginAttempts();
        userRepository.save(user);
        throw new BadCredentialsException("Invalid credentials");
    }

    // ADD: Reset failed attempts on successful login
    user.resetFailedLoginAttempts();
    userRepository.save(user);
    // ... rest of login flow
}
```

Also add lockout check in `verifyOtp()` to prevent bypassing lockout via OTP verification:

```java
public AuthResponse verifyOtp(VerifyOtpRequest request) {
    User user = userRepository.findByUsername(request.getUsername())
        .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));
    
    // ADD: Check lockout
    if (!user.isLoginAttemptAllowed()) {
        throw new AccountLockedException("Account is temporarily locked.");
    }
    // ... rest of OTP verification
}
```

**Required New Exception Class:**
Create `AccountLockedException.java` in `identity-service/src/main/java/com/miniurl/identity/exception/`:

```java
package com.miniurl.identity.exception;

public class AccountLockedException extends RuntimeException {
    public AccountLockedException(String message) {
        super(message);
    }
}
```

Add handler in identity-service's `GlobalExceptionHandler` (create if not present) to return HTTP 423 LOCKED.

**Required Tests:**

| Test | What It Verifies |
|---|---|
| `shouldLockAccountAfter5FailedLogins()` | 5 wrong passwords → 6th attempt returns 423 |
| `shouldResetFailedAttemptsOnSuccessfulLogin()` | 4 fails + 1 success → next fail starts count at 1 |
| `shouldAllowLoginAfterLockoutExpires()` | Wait 5 min → login succeeds again |
| `shouldNotIncrementOnNonexistentUser()` | Wrong username → no counter increment (anti-enumeration) |
| `shouldBlockOtpVerificationWhenLocked()` | Locked account → OTP verify returns 423 |
| `shouldReturnRemainingLockoutTime()` | Response includes seconds remaining |

**Acceptance Criteria:**
- [ ] 6 failed logins (5 attempts + 1 locked) returns HTTP 423 with lockout message
- [ ] Successful login resets the counter
- [ ] Lockout expires after 5 minutes
- [ ] OTP verification blocked when account locked
- [ ] Existing login tests still pass

**Verification Command:**
```bash
# 5 failed logins should succeed with 400, 6th should return 423
for i in {1..6}; do
  echo "Attempt $i:"
  curl -s -X POST http://localhost:8080/api/auth/login \
    -H "Content-Type: application/json" \
    -d '{"username":"testuser","password":"wrong"}' | jq '.message'
done
# Attempts 1-5: "Invalid credentials"
# Attempt 6: "Account is temporarily locked..."
```

**Estimated Risk if Fixed Incorrectly:**
- **Medium.** If lockout check is placed after password validation, attackers can still enumerate valid usernames via timing differences. If `isLoginAttemptAllowed()` has a logic bug, legitimate users could be permanently locked.
- **Mitigation:** Port the exact logic from monolith's [`User.java`](miniurl-monolith/src/main/java/com/miniurl/entity/User.java:200) entity methods. Write tests that mirror [`UserLockoutTest.java`](miniurl-monolith/src/test/java/com/miniurl/entity/UserLockoutTest.java:1).

**Implementation Order:** 2nd

---

### P0-3: Delete Account Accepts userId from Body Instead of JWT

**Root Cause:**
The monolith's [`AuthController.deleteAccount()`](miniurl-monolith/src/main/java/com/miniurl/controller/AuthController.java:125) extracts the authenticated user's identity from the JWT token via `SecurityContextHolder`. The microservices [`AuthController.deleteAccount()`](identity-service/src/main/java/com/miniurl/identity/controller/AuthController.java:125) accepts a `DeleteAccountRequest` body containing `userId`. This means any authenticated user can delete any other user's account by supplying their userId.

**Exact Files Requiring Changes:**

| File | Change Type |
|---|---|
| [`AuthController.java`](identity-service/src/main/java/com/miniurl/identity/controller/AuthController.java:125) | Remove body parameter, extract from JWT |
| [`AuthService.java`](identity-service/src/main/java/com/miniurl/identity/service/AuthService.java:641) | Change method signature |
| `DeleteAccountRequest.java` (if exists) | Delete or repurpose |

**Required Code Changes:**

In [`AuthController.java`](identity-service/src/main/java/com/miniurl/identity/controller/AuthController.java:125), change from:

```java
// CURRENT (VULNERABLE):
@PostMapping("/delete-account")
public ResponseEntity<ApiResponse> deleteAccount(@RequestBody DeleteAccountRequest request) {
    authService.deleteAccount(request.getUserId());
    return ResponseEntity.ok(ApiResponse.success("Account deleted"));
}
```

To:

```java
// FIXED:
@PostMapping("/delete-account")
public ResponseEntity<ApiResponse> deleteAccount(Authentication authentication) {
    String username = authentication.getName();
    authService.deleteAccount(username);
    return ResponseEntity.ok(ApiResponse.success("Account deleted successfully"));
}
```

In [`AuthService.java`](identity-service/src/main/java/com/miniurl/identity/service/AuthService.java:641), change method signature from `deleteAccount(Long userId)` to `deleteAccount(String username)`:

```java
@Transactional
public void deleteAccount(String username) {
    User user = userRepository.findByUsername(username)
        .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    
    user.setStatus(UserStatus.DELETED);
    userRepository.save(user);
    
    // Send outbox event
    outboxService.saveEvent("USER", user.getId().toString(), "ACCOUNT_DELETION",
        Map.of("username", user.getUsername(), "email", user.getEmail()));
}
```

**Required Tests:**

| Test | What It Verifies |
|---|---|
| `shouldDeleteOwnAccount()` | User A deletes own account → 200 |
| `shouldNotDeleteOtherAccount()` | User A tries to delete User B → 403 or deletes only own |
| `shouldRequireAuthentication()` | No token → 401 |
| `shouldRejectUserIdInBody()` | Even if userId is in body, it's ignored |

**Acceptance Criteria:**
- [ ] `delete-account` endpoint no longer accepts a request body
- [ ] User identity is extracted from the JWT token
- [ ] User A cannot delete User B's account
- [ ] Response matches monolith format: `{success: true, message: "Account deleted successfully"}`

**Verification Command:**
```bash
# Login as user A, get token
TOKEN_A=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"userA","password":"pass"}' | jq -r '.data.token')

# Try to delete with userId in body (should ignore body, delete user A)
curl -s -X POST http://localhost:8080/api/auth/delete-account \
  -H "Authorization: Bearer $TOKEN_A" \
  -H "Content-Type: application/json" \
  -d '{"userId":999}' | jq .
# Should delete user A, not user 999
```

**Estimated Risk if Fixed Incorrectly:**
- **Low.** The fix is straightforward — remove the body parameter and use `SecurityContextHolder`. The main risk is breaking the frontend if it currently sends `userId` in the body. The frontend must be updated to stop sending the body.
- **Mitigation:** Make the body optional (accept but ignore) during a transition period, then remove.

**Implementation Order:** 3rd

---

### P0-4: verify-email Endpoint Calls Wrong Service Method

**Root Cause:**
The monolith's [`AuthController.verifyEmail()`](miniurl-monolith/src/main/java/com/miniurl/controller/AuthController.java:65) validates a **password reset token** using `authService.verifyResetPasswordToken(token)`. The microservices [`AuthController.verifyEmail()`](identity-service/src/main/java/com/miniurl/identity/controller/AuthController.java:71) incorrectly calls `authService.verifyEmailInviteToken(token)`. This means the password reset flow is completely broken — clicking the reset link in an email validates against the wrong token type.

**Exact Files Requiring Changes:**

| File | Change Type |
|---|---|
| [`AuthController.java`](identity-service/src/main/java/com/miniurl/identity/controller/AuthController.java:71) | Fix method call |
| [`AuthService.java`](identity-service/src/main/java/com/miniurl/identity/service/AuthService.java:251) | Ensure `verifyResetPasswordToken()` exists |

**Required Code Changes:**

In [`AuthController.java`](identity-service/src/main/java/com/miniurl/identity/controller/AuthController.java:71), change:

```java
// CURRENT (BROKEN):
@GetMapping("/verify-email")
public ResponseEntity<ApiResponse> verifyEmail(@RequestParam String token) {
    String email = authService.verifyEmailInviteToken(token);  // WRONG METHOD
    return ResponseEntity.ok(ApiResponse.success("Email verified", Map.of("email", email)));
}
```

To:

```java
// FIXED:
@GetMapping("/verify-email")
public ResponseEntity<ApiResponse> verifyEmail(@RequestParam String token) {
    authService.verifyResetPasswordToken(token);
    return ResponseEntity.ok(ApiResponse.success("Email verified successfully"));
}
```

**Note:** The monolith's `verifyResetPasswordToken()` returns `void` and throws exceptions for invalid/expired tokens. The response format should match: `{success: true, message: "Email verified successfully"}`.

**Required Tests:**

| Test | What It Verifies |
|---|---|
| `shouldVerifyValidResetToken()` | Valid reset token → 200 |
| `shouldRejectExpiredResetToken()` | Expired token → 400 |
| `shouldRejectInvalidResetToken()` | Garbage token → 400 |
| `shouldRejectInviteTokenForResetVerification()` | Invite token used on verify-email → 400 |

**Acceptance Criteria:**
- [ ] `GET /api/auth/verify-email?token=VALID_RESET_TOKEN` returns 200
- [ ] `GET /api/auth/verify-email?token=INVITE_TOKEN` returns 400 (not silently accepted)
- [ ] Response format matches monolith

**Verification Command:**
```bash
# Request password reset to get a token
curl -s -X POST http://localhost:8080/api/auth/forgot-password \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com"}'

# Extract token from email/DB, then verify
curl -s "http://localhost:8080/api/auth/verify-email?token=REAL_RESET_TOKEN" | jq .
# Expected: {"success":true,"message":"Email verified successfully"}
```

**Estimated Risk if Fixed Incorrectly:**
- **Low.** This is a one-line fix. The risk is that `verifyResetPasswordToken()` might not exist or might have a different signature than expected. Verify the method exists before making the controller change.
- **Mitigation:** Check [`AuthService.java`](identity-service/src/main/java/com/miniurl/identity/service/AuthService.java:251) for the correct method name and signature.

**Implementation Order:** 4th

---

### P0-5: No Anti-Enumeration on Forgot-Password

**Root Cause:**
The monolith's [`AuthController.forgotPassword()`](miniurl-monolith/src/main/java/com/miniurl/controller/AuthController.java:77) returns the **same response** whether the email exists or not: `"If an account with that email exists, a password reset link has been sent."` The microservices [`AuthController.forgotPassword()`](identity-service/src/main/java/com/miniurl/identity/controller/AuthController.java:77) returns different responses for existing vs non-existing emails, allowing attackers to enumerate valid email addresses.

**Exact Files Requiring Changes:**

| File | Change Type |
|---|---|
| [`AuthController.java`](identity-service/src/main/java/com/miniurl/identity/controller/AuthController.java:77) | Change response to always be identical |
| [`AuthService.java`](identity-service/src/main/java/com/miniurl/identity/service/AuthService.java:319) | Don't throw on non-existent email |

**Required Code Changes:**

In [`AuthService.java`](identity-service/src/main/java/com/miniurl/identity/service/AuthService.java:319), modify `forgotPassword()` to silently return when email doesn't exist:

```java
@Transactional
public void forgotPassword(String email) {
    Optional<User> userOpt = userRepository.findByEmail(email);
    
    if (userOpt.isEmpty()) {
        // Anti-enumeration: pretend we sent an email
        log.info("Password reset requested for non-existent email: {}", email);
        return;
    }
    
    User user = userOpt.get();
    // Check 20-min cooldown
    // Generate token, save, send email via outbox
    // ... existing logic
}
```

In [`AuthController.java`](identity-service/src/main/java/com/miniurl/identity/controller/AuthController.java:77), ensure the response is always:

```java
@PostMapping("/forgot-password")
public ResponseEntity<ApiResponse> forgotPassword(@RequestBody @Valid ForgotPasswordRequest request) {
    authService.forgotPassword(request.getEmail());
    // Always return the same message
    return ResponseEntity.ok(ApiResponse.success(
        "If an account with that email exists, a password reset link has been sent."));
}
```

**Required Tests:**

| Test | What It Verifies |
|---|---|
| `shouldReturnSameResponseForExistingEmail()` | Existing email → generic success message |
| `shouldReturnSameResponseForNonExistentEmail()` | Non-existent email → same generic message |
| `shouldHaveIdenticalResponseBodies()` | Both responses are byte-for-byte identical |
| `shouldHaveIdenticalResponseTimes()` | Timing should not leak existence (add random delay if needed) |

**Acceptance Criteria:**
- [ ] Response body is identical for existing and non-existing emails
- [ ] HTTP status code is identical (200 in both cases)
- [ ] Response time is within 50ms for both cases (no timing oracle)

**Verification Command:**
```bash
RESP1=$(curl -s -X POST http://localhost:8080/api/auth/forgot-password \
  -H "Content-Type: application/json" \
  -d '{"email":"exists@test.com"}')
RESP2=$(curl -s -X POST http://localhost:8080/api/auth/forgot-password \
  -H "Content-Type: application/json" \
  -d '{"email":"doesnotexist@test.com"}')
diff <(echo "$RESP1") <(echo "$RESP2") && echo "PASS: Identical responses" || echo "FAIL: Different responses"
```

**Estimated Risk if Fixed Incorrectly:**
- **Low.** The fix is to catch the exception/empty optional and return the same success response. The only risk is accidentally suppressing real errors (e.g., DB connection failure) — make sure to only catch "user not found" specifically, not all exceptions.
- **Mitigation:** Use `Optional.isEmpty()` check rather than try/catch.

**Implementation Order:** 5th

---

### P0-14 (Phase 1 portion): Key Regeneration on Restart — Immediate Mitigation

**Root Cause:**
[`KeyService.java`](identity-service/src/main/java/com/miniurl/identity/service/KeyService.java:26) generates a new RSA 2048-bit key pair at `@PostConstruct` time, stored only in memory. On every restart, all existing JWTs become invalid because the public key changes. This is a **P0 operational concern** — every deploy logs out all users.

**Note:** Full fix is in Phase 4. Phase 1 applies an immediate mitigation.

**Exact Files Requiring Changes (Phase 1 mitigation):**

| File | Change Type |
|---|---|
| [`KeyService.java`](identity-service/src/main/java/com/miniurl/identity/service/KeyService.java:26) | Add file-based persistence as fallback |
| [`application.yml`](identity-service/src/main/resources/application.yml:1) | Add key storage path config |

**Required Code Changes (Immediate Mitigation):**

In [`KeyService.java`](identity-service/src/main/java/com/miniurl/identity/service/KeyService.java:26), modify `init()` to attempt loading keys from files before generating new ones:

```java
@Value("${app.security.key-storage-path:./keys}")
private String keyStoragePath;

@PostConstruct
public void init() {
    try {
        // Try to load existing keys from filesystem
        KeyPair loadedPair = loadKeyPairFromFiles();
        if (loadedPair != null) {
            this.keyPair = loadedPair;
            log.info("Loaded existing RSA key pair from: {}", keyStoragePath);
            return;
        }
    } catch (Exception e) {
        log.warn("Could not load existing keys, generating new pair: {}", e.getMessage());
    }
    
    // Generate new pair only if loading failed
    generateNewKeyPair();
    saveKeyPairToFiles();
    log.warn("Generated NEW RSA key pair — all existing tokens invalidated");
}

private void generateNewKeyPair() {
    KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
    gen.initialize(2048);
    this.keyPair = gen.generateKeyPair();
}

private void saveKeyPairToFiles() {
    Path dir = Path.of(keyStoragePath);
    Files.createDirectories(dir);
    // Save private key (PKCS#8 PEM)
    Files.write(dir.resolve("private.key"), 
        keyPair.getPrivate().getEncoded());
    // Save public key (X.509 PEM)
    Files.write(dir.resolve("public.key"), 
        keyPair.getPublic().getEncoded());
}

private KeyPair loadKeyPairFromFiles() {
    Path dir = Path.of(keyStoragePath);
    Path privPath = dir.resolve("private.key");
    Path pubPath = dir.resolve("public.key");
    if (!Files.exists(privPath) || !Files.exists(pubPath)) return null;
    
    byte[] privBytes = Files.readAllBytes(privPath);
    byte[] pubBytes = Files.readAllBytes(pubPath);
    
    KeyFactory kf = KeyFactory.getInstance("RSA");
    PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(privBytes));
    PublicKey pub = kf.generatePublic(new X509EncodedKeySpec(pubBytes));
    return new KeyPair(pub, priv);
}
```

**Required Config Changes:**

In [`application.yml`](identity-service/src/main/resources/application.yml:1), add:

```yaml
app:
  security:
    key-storage-path: ${KEY_STORAGE_PATH:./keys}
```

**Required Tests:**

| Test | What It Verifies |
|---|---|
| `shouldPersistKeysAcrossRestarts()` | Generate keys, simulate restart, same public key |
| `shouldGenerateNewKeysIfNoneExist()` | No key files → new keys generated |
| `shouldLoadExistingKeysOnStartup()` | Key files exist → loaded, not regenerated |

**Acceptance Criteria:**
- [ ] Keys persist to filesystem on first startup
- [ ] Subsequent startups load existing keys (no regeneration)
- [ ] JWTs issued before restart remain valid after restart
- [ ] Key files are created with restrictive permissions (owner read only)

**Verification Command:**
```bash
# Start identity service, get JWKS
curl -s http://localhost:8081/.well-known/jwks.json | jq '.keys[0].kid' > /tmp/kid1.txt

# Restart identity service
# Get JWKS again
curl -s http://localhost:8081/.well-known/jwks.json | jq '.keys[0].kid' > /tmp/kid2.txt

diff /tmp/kid1.txt /tmp/kid2.txt && echo "PASS: Same key" || echo "FAIL: Key changed"
```

**Estimated Risk if Fixed Incorrectly:**
- **Medium.** If file permissions are wrong, private key could be world-readable. If the storage path is on ephemeral storage (container restart), keys are still lost.
- **Mitigation:** Set file permissions to 600. In K8s, mount a PersistentVolumeClaim at the key storage path. Document that the PVC must survive pod restarts.

**Implementation Order:** 6th (immediate mitigation; full fix in Phase 4)

---

## Phase 2: Missing Controller/API Parity

**Goal:** Restore all controller surfaces present in the monolith.
**Services affected:** identity-service, feature-service, api-gateway
**Risk of not fixing:** Users and admins cannot perform essential functions

---

### P0-6: Missing AdminController — All Admin Endpoints

**Root Cause:**
The monolith's [`AdminController`](miniurl-monolith/src/main/java/com/miniurl/controller/AdminController.java:1) (391 lines) provides full user management, statistics, and audit-logged admin operations. The microservices have no admin controller at all. The identity service has the `UserRepository`, `RoleRepository`, and `AuditLog` entity — all the building blocks exist but are not wired together.

**Exact Files Requiring Changes:**

| File | Change Type |
|---|---|
| `identity-service/src/main/java/com/miniurl/identity/controller/AdminController.java` | **CREATE** |
| `identity-service/src/main/java/com/miniurl/identity/service/AdminService.java` | **CREATE** |
| [`SecurityConfig.java`](identity-service/src/main/java/com/miniurl/identity/config/SecurityConfig.java:31) | Add admin route protection |
| [`application.yml`](api-gateway/src/main/resources/application.yml:1) | Add admin route |

**Required Code Changes:**

**1. Create [`AdminController.java`](identity-service/src/main/java/com/miniurl/identity/controller/AdminController.java):**

Port from monolith's [`AdminController`](miniurl-monolith/src/main/java/com/miniurl/controller/AdminController.java:1). Key endpoints:

```java
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {
    
    // GET /api/admin/users — paginated, searchable, filterable by status
    @GetMapping("/users")
    public ResponseEntity<ApiResponse<PagedResponse<UserResponse>>> getUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) { ... }
    
    // GET /api/admin/users/{id}
    @GetMapping("/users/{id}")
    public ResponseEntity<ApiResponse<UserResponse>> getUser(@PathVariable Long id) { ... }
    
    // GET /api/admin/users/search
    @GetMapping("/users/search")
    public ResponseEntity<ApiResponse<List<UserResponse>>> searchUsers(
            @RequestParam String q) { ... }
    
    // POST /api/admin/users/{id}/deactivate
    @PostMapping("/users/{id}/deactivate")
    public ResponseEntity<ApiResponse> deactivateUser(@PathVariable Long id) { ... }
    
    // POST /api/admin/users/{id}/activate
    @PostMapping("/users/{id}/activate")
    public ResponseEntity<ApiResponse> activateUser(@PathVariable Long id) { ... }
    
    // POST /api/admin/users/{id}/suspend
    @PostMapping("/users/{id}/suspend")
    public ResponseEntity<ApiResponse> suspendUser(@PathVariable Long id) { ... }
    
    // POST /api/admin/users/{id}/role
    @PostMapping("/users/{id}/role")
    public ResponseEntity<ApiResponse> changeRole(@PathVariable Long id, 
            @RequestBody ChangeRoleRequest request) { ... }
    
    // GET /api/admin/stats
    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<AdminStats>> getStats() { ... }
}
```

**2. Create `AdminService.java`:**

Port business logic from monolith's [`AdminController`](miniurl-monolith/src/main/java/com/miniurl/controller/AdminController.java:1) into a dedicated service. Include:
- User pagination with search, status filter, sort validation
- User deactivation/activation/suspension with audit logging
- Role change with validation
- Statistics aggregation (total users, active users, URLs per user, etc.)

**3. Update [`SecurityConfig.java`](identity-service/src/main/java/com/miniurl/identity/config/SecurityConfig.java:31):**

Add admin path protection:

```java
.authorizeHttpRequests(auth -> auth
    .requestMatchers("/api/admin/**").hasRole("ADMIN")
    .requestMatchers("/api/auth/**").permitAll()
    // ... existing rules
)
```

**4. Update Gateway [`application.yml`](api-gateway/src/main/resources/application.yml:1):**

Ensure `/api/admin/**` routes to identity-service.

**Required Database/Config Changes:**
- Ensure `audit_logs` table exists in identity DB (already in [`init-identity-db.sql`](scripts/init-identity-db.sql:1))
- Create `AuditLogRepository` if not present
- Create `AuditLogService` for writing audit entries

**Required Tests:**

| Test | What It Verifies |
|---|---|
| `shouldListUsersPaginated()` | GET /admin/users returns paginated results |
| `shouldFilterUsersByStatus()` | ?status=ACTIVE returns only active users |
| `shouldSearchUsers()` | ?search=john returns matching users |
| `shouldDeactivateUser()` | POST /admin/users/{id}/deactivate soft-deletes |
| `shouldActivateUser()` | POST /admin/users/{id}/activate restores |
| `shouldSuspendUser()` | POST /admin/users/{id}/suspend sets SUSPENDED |
| `shouldChangeUserRole()` | POST /admin/users/{id}/role changes role |
| `shouldReturnStats()` | GET /admin/stats returns counts |
| `shouldRejectNonAdmin()` | Regular user → 403 |
| `shouldAuditLogAdminActions()` | Each action writes to audit_logs |

**Acceptance Criteria:**
- [ ] All 8 admin endpoints respond correctly
- [ ] Non-admin users receive 403
- [ ] Audit log entries are written for all mutating operations
- [ ] Response format uses `ApiResponse` wrapper
- [ ] Sort validation rejects invalid sort fields (matches monolith's [`validateUserSortField()`](miniurl-monolith/src/main/java/com/miniurl/controller/AdminController.java:377))

**Verification Command:**
```bash
# Get admin token
ADMIN_TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"adminpass"}' | jq -r '.data.token')

# Test admin endpoints
curl -s http://localhost:8080/api/admin/users?page=0&size=5 \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq '.data.content | length'
# Expected: 5

curl -s http://localhost:8080/api/admin/stats \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq '.data'
# Expected: {totalUsers: ..., activeUsers: ..., ...}
```

**Estimated Risk if Fixed Incorrectly:**
- **Medium.** Admin endpoints are powerful. If `@PreAuthorize` is misconfigured, regular users could access admin functions. If audit logging fails silently, malicious admin actions would be untraceable.
- **Mitigation:** Write integration tests that verify 403 for non-admin users. Make audit logging fail loudly (throw exception) if it can't write.

**Implementation Order:** 7th

---

### P0-7: Missing ProfileController

**Root Cause:**
The monolith's [`ProfileController`](miniurl-monolith/src/main/java/com/miniurl/controller/ProfileController.java:1) (160 lines) provides `GET /api/profile` and `PUT /api/profile` with partial updates and audit logging. The microservices have no profile endpoints.

**Exact Files Requiring Changes:**

| File | Change Type |
|---|---|
| `identity-service/src/main/java/com/miniurl/identity/controller/ProfileController.java` | **CREATE** |
| [`AuthService.java`](identity-service/src/main/java/com/miniurl/identity/service/AuthService.java:641) | Add `updateProfile()` method |

**Required Code Changes:**

**1. Create `ProfileController.java`:**

```java
@RestController
@RequestMapping("/api/profile")
public class ProfileController {
    
    @GetMapping
    public ResponseEntity<ApiResponse<ProfileResponse>> getProfile(
            Authentication authentication) {
        String username = authentication.getName();
        ProfileResponse profile = authService.getProfile(username);
        return ResponseEntity.ok(ApiResponse.success(profile));
    }
    
    @PutMapping
    public ResponseEntity<ApiResponse<ProfileResponse>> updateProfile(
            Authentication authentication,
            @RequestBody @Valid UpdateProfileRequest request) {
        String username = authentication.getName();
        ProfileResponse updated = authService.updateProfile(username, request);
        return ResponseEntity.ok(ApiResponse.success("Profile updated", updated));
    }
}
```

**2. Add to [`AuthService.java`](identity-service/src/main/java/com/miniurl/identity/service/AuthService.java:641):**

```java
public ProfileResponse getProfile(String username) {
    User user = userRepository.findByUsername(username)
        .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    return ProfileResponse.from(user);
}

@Transactional
public ProfileResponse updateProfile(String username, UpdateProfileRequest request) {
    User user = userRepository.findByUsername(username)
        .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    
    if (request.getFirstName() != null) user.setFirstName(request.getFirstName());
    if (request.getLastName() != null) user.setLastName(request.getLastName());
    if (request.getTheme() != null) user.setTheme(request.getTheme());
    
    userRepository.save(user);
    return ProfileResponse.from(user);
}
```

**Required Tests:**

| Test | What It Verifies |
|---|---|
| `shouldGetOwnProfile()` | GET /api/profile returns user data |
| `shouldUpdateProfile()` | PUT /api/profile updates fields |
| `shouldPartiallyUpdateProfile()` | Only provided fields change |
| `shouldRequireAuthentication()` | No token → 401 |

**Acceptance Criteria:**
- [ ] `GET /api/profile` returns user's profile with theme
- [ ] `PUT /api/profile` supports partial updates
- [ ] Response includes `firstName`, `lastName`, `email`, `username`, `role`, `theme`, `createdAt`

**Verification Command:**
```bash
curl -s http://localhost:8080/api/profile \
  -H "Authorization: Bearer $TOKEN" | jq '.data | keys'
# Expected: ["firstName","lastName","email","username","role","theme","createdAt"]
```

**Estimated Risk if Fixed Incorrectly:**
- **Low.** Profile endpoints are read-heavy with simple updates. Risk is in allowing users to update fields they shouldn't (e.g., role, email without verification).
- **Mitigation:** Use a whitelist approach — only copy specific allowed fields from the request.

**Implementation Order:** 8th

---

### P0-8: Missing SettingsController

**Root Cause:**
The monolith's [`SettingsController`](miniurl-monolith/src/main/java/com/miniurl/controller/SettingsController.java:1) (153 lines) provides `GET /api/settings/export` (JSON data export) and `POST /api/settings/delete-account` (password-confirmed account deletion). The microservices have no settings endpoints.

**Exact Files Requiring Changes:**

| File | Change Type |
|---|---|
| `identity-service/src/main/java/com/miniurl/identity/controller/SettingsController.java` | **CREATE** |
| [`AuthService.java`](identity-service/src/main/java/com/miniurl/identity/service/AuthService.java:641) | Add `exportUserData()`, `deleteAccountWithPassword()` |

**Required Code Changes:**

**1. Create `SettingsController.java`:**

```java
@RestController
@RequestMapping("/api/settings")
public class SettingsController {
    
    @GetMapping("/export")
    public ResponseEntity<ApiResponse<Map<String, Object>>> exportData(
            Authentication authentication) {
        String username = authentication.getName();
        Map<String, Object> exportData = authService.exportUserData(username);
        return ResponseEntity.ok(ApiResponse.success("Data exported", exportData));
    }
    
    @PostMapping("/delete-account")
    public ResponseEntity<ApiResponse> deleteAccount(
            Authentication authentication,
            @RequestBody @Valid DeleteAccountRequest request) {
        String username = authentication.getName();
        authService.deleteAccountWithPassword(username, request.getPassword());
        return ResponseEntity.ok(ApiResponse.success("Account deleted successfully"));
    }
}
```

**2. Add to [`AuthService.java`](identity-service/src/main/java/com/miniurl/identity/service/AuthService.java:641):**

```java
public Map<String, Object> exportUserData(String username) {
    User user = userRepository.findByUsername(username)
        .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    
    Map<String, Object> export = new LinkedHashMap<>();
    export.put("user", Map.of(
        "firstName", user.getFirstName(),
        "lastName", user.getLastName(),
        "email", user.getEmail(),
        "username", user.getUsername(),
        "createdAt", user.getCreatedAt().toString()
    ));
    // Note: URLs are in a separate service. This endpoint should call
    // the URL service internally to get the user's URLs, or document
    // that URLs must be exported separately.
    export.put("urls", List.of()); // Placeholder — see P1 note
    
    return export;
}

@Transactional
public void deleteAccountWithPassword(String username, String password) {
    User user = userRepository.findByUsername(username)
        .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    
    if (!passwordEncoder.matches(password, user.getPassword())) {
        throw new BadCredentialsException("Invalid password");
    }
    
    user.setStatus(UserStatus.DELETED);
    userRepository.save(user);
    
    outboxService.saveEvent("USER", user.getId().toString(), "ACCOUNT_DELETION",
        Map.of("username", user.getUsername(), "email", user.getEmail()));
}
```

**Required Tests:**

| Test | What It Verifies |
|---|---|
| `shouldExportUserData()` | GET /api/settings/export returns JSON with user info |
| `shouldDeleteAccountWithCorrectPassword()` | Correct password → 200, account soft-deleted |
| `shouldRejectDeleteWithWrongPassword()` | Wrong password → 400 |
| `shouldRequireAuthentication()` | No token → 401 |

**Acceptance Criteria:**
- [ ] `GET /api/settings/export` returns user data as JSON
- [ ] `POST /api/settings/delete-account` requires password confirmation
- [ ] Wrong password returns 400
- [ ] Account is soft-deleted (status=DELETED), not hard-deleted

**Verification Command:**
```bash
# Export data
curl -s http://localhost:8080/api/settings/export \
  -H "Authorization: Bearer $TOKEN" | jq '.data.user.username'

# Delete account with password
curl -s -X POST http://localhost:8080/api/settings/delete-account \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"password":"correctpassword"}' | jq .
```

**Estimated Risk if Fixed Incorrectly:**
- **Medium.** The `delete-account` endpoint is destructive. If password validation is bypassed, accounts could be deleted without proper authentication. The export endpoint could leak data if authentication is misconfigured.
- **Mitigation:** Ensure both endpoints extract identity from JWT, not request body. Test with wrong password explicitly.

**Implementation Order:** 9th

---

### P0-9: Missing SelfInviteController

**Root Cause:**
The monolith's [`SelfInviteController`](miniurl-monolith/src/main/java/com/miniurl/controller/SelfInviteController.java:1) (123 lines) provides `POST /api/self-invite/send` which checks the `GLOBAL_USER_SIGNUP` global flag before allowing public signup requests. The microservices have no self-invite endpoint.

**Exact Files Requiring Changes:**

| File | Change Type |
|---|---|
| `identity-service/src/main/java/com/miniurl/identity/controller/SelfInviteController.java` | **CREATE** |
| `identity-service/src/main/java/com/miniurl/identity/service/SelfInviteService.java` | **CREATE** |
| [`SecurityConfig.java`](identity-service/src/main/java/com/miniurl/identity/config/SecurityConfig.java:31) | Add public route |
| [`application.yml`](api-gateway/src/main/resources/application.yml:1) | Add route |

**Required Code Changes:**

**1. Create `SelfInviteController.java`:**

```java
@RestController
@RequestMapping("/api/self-invite")
public class SelfInviteController {
    
    @PostMapping("/send")
    public ResponseEntity<ApiResponse> sendSelfInvite(
            @RequestParam String email,
            @RequestParam String baseUrl) {
        // Validate email format
        if (email == null || !email.matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")) {
            return ResponseEntity.badRequest()
                .body(ApiResponse.error("Invalid email format"));
        }
        
        selfInviteService.processSelfInvite(email, baseUrl);
        return ResponseEntity.ok(ApiResponse.success(
            "If signups are enabled, an invitation will be sent to your email."));
    }
}
```

**2. Create `SelfInviteService.java`:**

```java
@Service
public class SelfInviteService {
    
    @Transactional
    public void processSelfInvite(String email, String baseUrl) {
        // Check GLOBAL_USER_SIGNUP flag via feature-service
        if (!globalFlagService.isGlobalFeatureEnabled("GLOBAL_USER_SIGNUP")) {
            log.info("Self-invite blocked: GLOBAL_USER_SIGNUP is disabled");
            return; // Silent — anti-enumeration
        }
        
        // Check if email already has a pending invite
        if (emailInviteRepository.existsByEmailAndStatus(email, InviteStatus.PENDING)) {
            log.info("Self-invite: pending invite already exists for {}", email);
            return; // Silent — don't reveal existing invites
        }
        
        // Create invite
        String token = generateInviteToken();
        EmailInvite invite = new EmailInvite(email, token, "SELF_INVITE");
        emailInviteRepository.save(invite);
        
        // Send invite email via outbox
        outboxService.saveEvent("USER", email, "INVITE",
            Map.of("email", email, "token", token, "baseUrl", baseUrl));
    }
}
```

**Required Cross-Service Integration:**
The `SelfInviteService` needs to check the `GLOBAL_USER_SIGNUP` global flag. This requires either:
- A direct HTTP call to the feature-service
- A shared Redis cache read
- A gRPC call

**Recommendation:** Use the same Redis cache that the feature-service populates. Read `global_flag:GLOBAL_USER_SIGNUP` from Redis directly. This avoids a synchronous HTTP dependency.

**Required Tests:**

| Test | What It Verifies |
|---|---|
| `shouldCreateInviteWhenSignupEnabled()` | GLOBAL_USER_SIGNUP=true → invite created |
| `shouldSilentlyIgnoreWhenSignupDisabled()` | GLOBAL_USER_SIGNUP=false → same response, no invite |
| `shouldRejectInvalidEmail()` | Bad email format → 400 |
| `shouldNotRevealExistingInvites()` | Duplicate email → same success response |
| `shouldBePublicEndpoint()` | No auth required → 200 |

**Acceptance Criteria:**
- [ ] `POST /api/self-invite/send` is publicly accessible (no auth)
- [ ] Checks `GLOBAL_USER_SIGNUP` flag before creating invite
- [ ] Returns identical response whether signup is enabled or disabled
- [ ] Sends invite email via outbox pattern

**Verification Command:**
```bash
# Test with signups enabled
curl -s -X POST "http://localhost:8080/api/self-invite/send?email=test@example.com&baseUrl=http://localhost:8080" | jq .
# Expected: {"success":true,"message":"If signups are enabled, an invitation will be sent..."}
```

**Estimated Risk if Fixed Incorrectly:**
- **Medium.** If the global flag check fails open (defaults to true when feature-service is down), the self-invite endpoint could allow signups when they should be disabled. If the flag check fails closed, legitimate users can't sign up.
- **Mitigation:** Default to closed (disabled) when the flag can't be read. Log a warning. Add a circuit breaker for the feature-service call.

**Implementation Order:** 10th

---

### P0-10: Missing Public /api/features/global Endpoint

**Root Cause:**
The monolith's [`FeatureFlagPublicController.getGlobalFlags()`](miniurl-monolith/src/main/java/com/miniurl/controller/FeatureFlagPublicController.java:115) exposes global flags at `GET /api/features/global` without authentication. The microservices have global flags at `/api/global-flags` in the feature-service, but this path is not routed as a public endpoint in the gateway, and it may require authentication.

**Exact Files Requiring Changes:**

| File | Change Type |
|---|---|
| [`FeatureController.java`](feature-service/src/main/java/com/miniurl/feature/controller/FeatureController.java:21) | Add public global flags endpoint |
| [`SecurityConfig.java`](api-gateway/src/main/java/com/miniurl/gateway/config/SecurityConfig.java:18) | Add public route |
| [`application.yml`](api-gateway/src/main/resources/application.yml:1) | Ensure route exists |

**Required Code Changes:**

**1. Add to [`FeatureController.java`](feature-service/src/main/java/com/miniurl/feature/controller/FeatureController.java:21):**

```java
@GetMapping("/global")
public ResponseEntity<ApiResponse<List<GlobalFlagResponse>>> getPublicGlobalFlags() {
    List<GlobalFlagResponse> flags = globalFlagService.getAllGlobalFlags();
    return ResponseEntity.ok(ApiResponse.success(flags));
}
```

**Note:** This endpoint must NOT require authentication. If the feature-service's SecurityConfig requires auth for `/api/features/**`, add a specific permit rule for `/api/features/global`.

**2. Update Gateway [`SecurityConfig.java`](api-gateway/src/main/java/com/miniurl/gateway/config/SecurityConfig.java:18):**

```java
.pathMatchers("/api/features/global").permitAll()
```

**3. Update Gateway [`application.yml`](api-gateway/src/main/resources/application.yml:1):**

Ensure `/api/features/**` routes to feature-service.

**Required Tests:**

| Test | What It Verifies |
|---|---|
| `shouldReturnGlobalFlagsWithoutAuth()` | No token → 200 with flag list |
| `shouldIncludeGLOBAL_USER_SIGNUP()` | Response includes GLOBAL_USER_SIGNUP flag |
| `shouldIncludeTWO_FACTOR_AUTH()` | Response includes TWO_FACTOR_AUTH flag |
| `shouldUseApiResponseWrapper()` | Response has `success`, `message`, `data` |

**Acceptance Criteria:**
- [ ] `GET /api/features/global` returns all global flags without authentication
- [ ] Response uses `ApiResponse` wrapper
- [ ] Frontend can check `GLOBAL_USER_SIGNUP` and `TWO_FACTOR_AUTH` flags

**Verification Command:**
```bash
curl -s http://localhost:8080/api/features/global | jq '.data | length'
# Expected: number of global flags > 0
```

**Estimated Risk if Fixed Incorrectly:**
- **Low.** This is a read-only endpoint. The main risk is accidentally exposing feature flags that should be admin-only. Global flags are intentionally public in the monolith design.
- **Mitigation:** Only expose global flags here, not role-based feature flags.

**Implementation Order:** 11th

---

### P0-11: Missing /api/features for Authenticated Users

**Root Cause:**
The monolith's [`FeatureFlagPublicController.getFeatures()`](miniurl-monolith/src/main/java/com/miniurl/controller/FeatureFlagPublicController.java:51) returns role-based features for the authenticated user at `GET /api/features`. The microservices have `GET /api/features/role/{roleId}` in the feature-service, but no endpoint that automatically resolves the user's role from their JWT.

**Exact Files Requiring Changes:**

| File | Change Type |
|---|---|
| [`FeatureController.java`](feature-service/src/main/java/com/miniurl/feature/controller/FeatureController.java:21) | Add user-aware endpoint |
| [`FeatureFlagService.java`](feature-service/src/main/java/com/miniurl/feature/service/FeatureFlagService.java:72) | Add `getFeaturesForUser()` |

**Required Code Changes:**

**1. Add to [`FeatureController.java`](feature-service/src/main/java/com/miniurl/feature/controller/FeatureController.java:21):**

```java
@GetMapping("/me")
public ResponseEntity<ApiResponse<List<FeatureFlagResponse>>> getMyFeatures(
        @RequestAttribute("userId") Long userId,
        @RequestAttribute("roles") List<String> roles) {
    List<FeatureFlagResponse> features = featureFlagService.getFeaturesForUser(roles);
    return ResponseEntity.ok(ApiResponse.success(features));
}
```

**Note:** The `@RequestAttribute` values must be populated by a gateway filter that extracts claims from the JWT and adds them as request attributes before forwarding.

**2. Add to [`FeatureFlagService.java`](feature-service/src/main/java/com/miniurl/feature/service/FeatureFlagService.java:72):**

```java
public List<FeatureFlagResponse> getFeaturesForUser(List<String> roles) {
    if (roles.contains("ROLE_ADMIN")) {
        return getAllFeatures(); // Admins see all features
    }
    // Find the user's role ID
    return featureFlagRepository.findByRoleNameIn(roles).stream()
        .map(this::convertToResponse)
        .collect(Collectors.toList());
}
```

**Required Gateway Changes:**
Add a global filter in the API gateway that:
1. Extracts roles from the JWT
2. Adds `X-User-Id` and `X-User-Roles` headers to proxied requests

**Required Tests:**

| Test | What It Verifies |
|---|---|
| `shouldReturnFeaturesForUserRole()` | Regular user → only USER features |
| `shouldReturnAllFeaturesForAdmin()` | Admin → all features |
| `shouldRequireAuthentication()` | No token → 401 |
| `shouldUseApiResponseWrapper()` | Response has `success`, `message`, `data` |

**Acceptance Criteria:**
- [ ] `GET /api/features` returns features based on authenticated user's role
- [ ] Admins see all features
- [ ] Regular users see only their role's features
- [ ] Response uses `ApiResponse` wrapper

**Verification Command:**
```bash
# As regular user
curl -s http://localhost:8080/api/features \
  -H "Authorization: Bearer $USER_TOKEN" | jq '.data | length'

# As admin
curl -s http://localhost:8080/api/features \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq '.data | length'
# Admin should see more features
```

**Estimated Risk if Fixed Incorrectly:**
- **Medium.** If role extraction from JWT is wrong, users could see admin-only features or vice versa. Feature flags control UI visibility and feature access.
- **Mitigation:** Test with multiple users of different roles. Verify admin-only features are not visible to regular users.

**Implementation Order:** 12th

---

## Phase 3: Event/Cache Consistency

**Goal:** Fix event-driven behavior and cache consistency issues.
**Services affected:** notification-service, redirect-service, url-service
**Risk of not fixing:** Silent data loss, stale data served to users

---

### P0-12: REGISTRATION_CONGRATS Event Not Handled

**Root Cause:**
The identity service's [`AuthService.registerUser()`](identity-service/src/main/java/com/miniurl/identity/service/AuthService.java:108) sends outbox events with type `REGISTRATION_CONGRATS`. The notification service's [`EmailService.sendEmail()`](notification-service/src/main/java/com/miniurl/notification/service/EmailService.java:53) has a `switch(eventType)` that handles 10 event types but has **no case for `REGISTRATION_CONGRATS`**. Registration congratulations emails are silently dropped.

**Exact Files Requiring Changes:**

| File | Change Type |
|---|---|
| [`EmailService.java`](notification-service/src/main/java/com/miniurl/notification/service/EmailService.java:53) | Add REGISTRATION_CONGRATS case |
| `notification-service/src/main/resources/templates/registration-congrats.html` | **CREATE** (Thymeleaf template) |

**Required Code Changes:**

In [`EmailService.java`](notification-service/src/main/java/com/miniurl/notification/service/EmailService.java:53), add to the switch statement:

```java
case "REGISTRATION_CONGRATS":
    sendRegistrationCongratsEmail(toEmail, username, payload);
    break;
```

Add the method:

```java
private void sendRegistrationCongratsEmail(String toEmail, String username, 
        Map<String, Object> payload) {
    Context context = createBaseContext(payload);
    context.setVariable("firstName", payload.getOrDefault("firstName", username));
    
    String html = templateEngine.process("registration-congrats", context);
    MimeMessage message = mailSender.createMimeMessage();
    MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
    helper.setTo(toEmail);
    helper.setSubject("Welcome to MiniURL!");
    helper.setText(html, true);
    mailSender.send(message);
    
    log.info("Sent registration congratulations email to: {}", toEmail);
}
```

**2. Create Thymeleaf template** `registration-congrats.html` in `notification-service/src/main/resources/templates/`:

Port the template from the monolith's email templates (refer to monolith's `sendCongratulationsEmail()` in [`EmailService.java`](miniurl-monolith/src/main/java/com/miniurl/service/EmailService.java:566)).

**Required Tests:**

| Test | What It Verifies |
|---|---|
| `shouldHandleRegistrationCongratsEvent()` | REGISTRATION_CONGRATS event → email sent |
| `shouldIncludeFirstNameInEmail()` | Email body contains user's first name |
| `shouldNotThrowOnMissingPayload()` | Missing optional fields → graceful degradation |

**Acceptance Criteria:**
- [ ] `REGISTRATION_CONGRATS` events trigger congratulations email
- [ ] Email contains the user's first name
- [ ] Email uses the correct Thymeleaf template
- [ ] All 10 existing event types still work (no regression)

**Verification Command:**
```bash
# Sign up a new user, then check notification service logs
# Or: check Kafka topic for REGISTRATION_CONGRATS event
kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic notifications --from-beginning | grep REGISTRATION_CONGRATS
```

**Estimated Risk if Fixed Incorrectly:**
- **Low.** This is additive — adding a new case to a switch statement. The only risk is if the template is missing or malformed, which would cause an exception in the notification service.
- **Mitigation:** Add a default case in the switch that logs unknown event types rather than silently ignoring them.

**Implementation Order:** 13th

---

### P0-15: No Cache Invalidation on URL Delete

**Root Cause:**
When a URL is deleted via [`UrlService.deleteUrl()`](url-service/src/main/java/com/miniurl/url/service/UrlService.java:300), the record is removed from the database but the Redis cache entry (`url:cache:{shortCode}`) in the redirect-service is not invalidated. The deleted URL remains redirectable for up to 1 hour (the Redis TTL).

**Exact Files Requiring Changes:**

| File | Change Type |
|---|---|
| [`UrlService.java`](url-service/src/main/java/com/miniurl/url/service/UrlService.java:300) | Add cache invalidation after delete |
| [`RedirectService.java`](redirect-service/src/main/java/com/miniurl/redirect/service/RedirectService.java:36) | Add cache invalidation endpoint or shared Redis |
| [`OutboxRelay.java`](url-service/src/main/java/com/miniurl/url/service/OutboxRelay.java:25) | Optionally send cache invalidation event |

**Required Code Changes:**

**Option A (Recommended): Shared Redis Instance**

If both url-service and redirect-service share the same Redis instance, the url-service can directly delete the cache key:

In [`UrlService.java`](url-service/src/main/java/com/miniurl/url/service/UrlService.java:300), modify `deleteUrl()`:

```java
@Transactional
public void deleteUrl(Long urlId, Long userId) {
    Url url = urlRepository.findByIdAndUserId(urlId, userId)
        .orElseThrow(() -> new ResourceNotFoundException("URL not found"));
    
    String shortCode = url.getShortCode();
    urlRepository.delete(url);
    
    // Invalidate Redis cache
    redisTemplate.delete("url:cache:" + shortCode);
    log.info("Invalidated cache for deleted URL: {}", shortCode);
    
    // Send outbox event
    outboxService.saveEvent("URL", urlId.toString(), "URL_DELETED",
        Map.of("shortCode", shortCode));
}
```

**Option B: Event-Driven Invalidation**

If Redis instances are separate, send a cache invalidation event via Kafka:

1. URL service publishes `URL_DELETED` event to `url-events` topic
2. Redirect service consumes `URL_DELETED` events and deletes from its Redis

Add to redirect-service:

```java
@KafkaListener(topics = "url-events", groupId = "redirect-group")
public void handleUrlEvent(UrlEvent event) {
    if ("URL_DELETED".equals(event.getType())) {
        redisTemplate.delete("url:cache:" + event.getShortCode());
        log.info("Cache invalidated for deleted URL: {}", event.getShortCode());
    }
}
```

**Required Tests:**

| Test | What It Verifies |
|---|---|
| `shouldInvalidateCacheOnUrlDelete()` | Delete URL → cache key removed |
| `shouldReturn404AfterDelete()` | Redirect after delete → 404 |
| `shouldNotAffectOtherCachedUrls()` | Delete URL A → URL B still cached |

**Acceptance Criteria:**
- [ ] Deleting a URL removes its Redis cache entry
- [ ] Redirecting to a deleted URL returns 404 (not the old target)
- [ ] Cache invalidation happens within 5 seconds of deletion (outbox relay interval)

**Verification Command:**
```bash
# Create URL, verify redirect works
SHORT_CODE=$(curl -s -X POST http://localhost:8080/api/urls \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"url":"https://example.com"}' | jq -r '.data.shortCode')

curl -v -o /dev/null "http://localhost:8080/r/$SHORT_CODE" 2>&1 | grep "< HTTP"
# Expected: 302

# Delete URL
URL_ID=$(curl -s http://localhost:8080/api/urls \
  -H "Authorization: Bearer $TOKEN" | jq -r '.data.content[0].id')
curl -s -X DELETE "http://localhost:8080/api/urls/$URL_ID" \
  -H "Authorization: Bearer $TOKEN"

# Wait for cache invalidation, then retry redirect
sleep 6
curl -v -o /dev/null "http://localhost:8080/r/$SHORT_CODE" 2>&1 | grep "< HTTP"
# Expected: 404
```

**Estimated Risk if Fixed Incorrectly:**
- **Medium.** If cache invalidation is overly aggressive (e.g., flush all on any delete), it could cause a thundering herd on the URL service. If invalidation fails silently, stale redirects persist.
- **Mitigation:** Only delete the specific cache key. Log and alert on invalidation failures. Consider using a shorter TTL (e.g., 5 min) during the transition period.

**Implementation Order:** 14th

---

## Phase 4: RSA/JWT Production Readiness

**Goal:** Harden JWT infrastructure for production deployments.
**Services affected:** identity-service, api-gateway
**Risk of not fixing:** Mass user logout on every deploy, no key rotation capability

---

### P0-14 (Full Fix): Persistent Key Storage with Rotation Support

**Root Cause:**
[`KeyService.java`](identity-service/src/main/java/com/miniurl/identity/service/KeyService.java:26) generates RSA keys in-memory at startup. Phase 1 added file-based persistence as mitigation. Phase 4 adds proper key rotation, multiple active keys, and K8s Secret integration.

**Exact Files Requiring Changes:**

| File | Change Type |
|---|---|
| [`KeyService.java`](identity-service/src/main/java/com/miniurl/identity/service/KeyService.java:26) | Full rewrite for multi-key support |
| [`JwtService.java`](identity-service/src/main/java/com/miniurl/identity/service/JwtService.java:29) | Add `kid` header to tokens |
| [`JwksController.java`](identity-service/src/main/java/com/miniurl/identity/controller/JwksController.java:22) | Return multiple keys |
| [`application.yml`](identity-service/src/main/resources/application.yml:1) | Add key rotation config |
| [`application.yml`](api-gateway/src/main/resources/application.yml:1) | JWKS cache config |

**Required Code Changes:**

**1. Rewrite [`KeyService.java`](identity-service/src/main/java/com/miniurl/identity/service/KeyService.java:26):**

```java
@Service
@Slf4j
public class KeyService {
    
    // Map of kid -> KeyPair for active keys
    private final Map<String, KeyPair> activeKeys = new ConcurrentHashMap<>();
    private String currentKid;
    
    @Value("${app.security.key-storage-path:./keys}")
    private String keyStoragePath;
    
    @Value("${app.security.key-rotation.retention-count:3}")
    private int keyRetentionCount;
    
    @PostConstruct
    public void init() {
        loadOrGenerateKeys();
        purgeOldKeys();
    }
    
    private void loadOrGenerateKeys() {
        Path dir = Path.of(keyStoragePath);
        if (Files.exists(dir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.key")) {
                for (Path keyFile : stream) {
                    String kid = keyFile.getFileName().toString().replace(".key", "");
                    KeyPair kp = loadKeyPair(kid);
                    if (kp != null) {
                        activeKeys.put(kid, kp);
                        log.info("Loaded key: {}", kid);
                    }
                }
            }
        }
        
        if (activeKeys.isEmpty()) {
            String kid = "miniurl-" + UUID.randomUUID().toString().substring(0, 8);
            generateAndStoreKey(kid);
        }
        
        // Set current key to the newest
        currentKid = activeKeys.keySet().stream()
            .max(Comparator.comparing(k -> k))
            .orElseThrow();
    }
    
    public String getCurrentKid() { return currentKid; }
    public PrivateKey getPrivateKey(String kid) { 
        KeyPair kp = activeKeys.get(kid);
        return kp != null ? kp.getPrivate() : null;
    }
    public PrivateKey getCurrentPrivateKey() { return getPrivateKey(currentKid); }
    
    public JWKSet getPublicJWKSet() {
        List<JWK> keys = activeKeys.entrySet().stream()
            .map(e -> {
                RSAKey jwk = new RSAKey.Builder((RSAPublicKey) e.getValue().getPublic())
                    .keyID(e.getKey())
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .build();
                return (JWK) jwk;
            })
            .collect(Collectors.toList());
        return new JWKSet(keys);
    }
    
    @Scheduled(cron = "${app.security.key-rotation.cron:0 0 2 * * SUN}") // Weekly Sunday 2AM
    public void rotateKeys() {
        String newKid = "miniurl-" + UUID.randomUUID().toString().substring(0, 8);
        generateAndStoreKey(newKid);
        currentKid = newKid;
        purgeOldKeys();
        log.info("Key rotation complete. New active kid: {}", newKid);
    }
    
    private void generateAndStoreKey(String kid) {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair kp = gen.generateKeyPair();
        activeKeys.put(kid, kp);
        saveKeyPair(kid, kp);
    }
    
    private void purgeOldKeys() {
        if (activeKeys.size() <= keyRetentionCount) return;
        List<String> sortedKids = new ArrayList<>(activeKeys.keySet());
        sortedKids.sort(Comparator.naturalOrder());
        while (sortedKids.size() > keyRetentionCount) {
            String oldKid = sortedKids.remove(0);
            activeKeys.remove(oldKid);
            try { Files.deleteIfExists(Path.of(keyStoragePath, oldKid + ".key")); } 
            catch (IOException e) { log.warn("Failed to delete old key: {}", oldKid); }
        }
    }
}
```

**2. Update [`JwtService.java`](identity-service/src/main/java/com/miniurl/identity/service/JwtService.java:29):**

Add `kid` header to all issued tokens:

```java
private String createToken(Map<String, Object> claims, String subject) {
    String kid = keyService.getCurrentKid();
    return Jwts.builder()
        .setHeaderParam("kid", kid)  // ADD THIS
        .setClaims(claims)
        .setSubject(subject)
        .setIssuedAt(new Date())
        .setExpiration(new Date(System.currentTimeMillis() + expirationMs))
        .signWith(keyService.getCurrentPrivateKey())
        .compact();
}
```

**3. Update Gateway [`application.yml`](api-gateway/src/main/resources/application.yml:1):**

Add JWKS caching:

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: ${IDENTITY_SERVICE_URL}/.well-known/jwks.json
          jws-algorithm: RS256
      jwk:
        cache:
          ttl: 300s  # Cache JWKS for 5 minutes
```

**Required Tests:**

| Test | What It Verifies |
|---|---|
| `shouldSignTokenWithKid()` | JWT header contains `kid` |
| `shouldReturnMultipleKeysInJwks()` | JWKS endpoint returns all active keys |
| `shouldRotateKeysOnSchedule()` | After rotation, new kid is current |
| `shouldRetainOldKeysForValidation()` | Tokens signed with old key still validate |
| `shouldPurgeKeysBeyondRetention()` | Only N most recent keys retained |
| `shouldSurviveRestart()` | Keys persist across restarts |

**Acceptance Criteria:**
- [ ] JWTs include `kid` header matching the signing key
- [ ] JWKS endpoint returns all active public keys
- [ ] Tokens signed with old keys remain valid (within retention window)
- [ ] Key rotation runs on schedule without manual intervention
- [ ] Keys survive service restarts

**Verification Command:**
```bash
# Get token and inspect kid
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"pass"}' | jq -r '.data.token')

# Decode header (base64)
echo $TOKEN | cut -d. -f1 | base64 -d | jq .
# Expected: {"alg":"RS256","kid":"miniurl-XXXXXXXX"}

# Verify JWKS contains the kid
curl -s http://localhost:8081/.well-known/jwks.json | jq '.keys[].kid'
```

**Estimated Risk if Fixed Incorrectly:**
- **High.** If key rotation deletes the active key too early, all tokens become invalid. If the JWKS endpoint doesn't include old keys, tokens signed before rotation fail validation. If the K8s PVC is lost, all keys are lost.
- **Mitigation:** Always retain at least 2 keys (current + previous). Test rotation in staging before production. Back up keys to a secure secret store (Vault, AWS KMS, K8s Secrets).

**Implementation Order:** 15th (after Phase 1 mitigation is stable)

---

## Phase 5: Integration Test Parity

**Goal:** Achieve test coverage comparable to the monolith.
**Services affected:** All
**Risk of not fixing:** Zero confidence in service integration, regressions undetected

---

### P0-13: Zero Integration Tests in Any Microservice

**Root Cause:**
The monolith has 8 integration test classes (~80 tests) using `@SpringBootTest`, `MockMvc`, GreenMail, and real database interactions. The microservices have only unit tests with mocked dependencies. There are **zero tests** that verify actual service-to-service communication, database operations, Kafka message flow, or Redis caching in a real environment.

**Exact Files Requiring Changes:**

This is a large effort spanning all services. Below is the prioritized test creation plan.

**Required Integration Tests (in priority order):**

| # | Test Class | Service | Tests Monolith Equivalent |
|---|---|---|---|
| 1 | `AuthenticationIntegrationTest` | identity-service | [`AuthenticationIntegrationTest`](miniurl-monolith/src/test/java/com/miniurl/integration/AuthenticationIntegrationTest.java:1) |
| 2 | `UrlCrudIntegrationTest` | url-service | [`UrlCrudIntegrationTest`](miniurl-monolith/src/test/java/com/miniurl/integration/UrlCrudIntegrationTest.java:1) |
| 3 | `TwoFactorAuthIntegrationTest` | identity-service | [`TwoFactorAuthIntegrationTest`](miniurl-monolith/src/test/java/com/miniurl/integration/TwoFactorAuthIntegrationTest.java:1) |
| 4 | `SecurityFeaturesIntegrationTest` | identity-service | [`SecurityFeaturesIntegrationTest`](miniurl-monolith/src/test/java/com/miniurl/integration/SecurityFeaturesIntegrationTest.java:1) |
| 5 | `FeatureFlagIntegrationTest` | feature-service | [`FeatureFlagIntegrationTest`](miniurl-monolith/src/test/java/com/miniurl/integration/FeatureFlagIntegrationTest.java:1) |
| 6 | `EmailIntegrationTest` | notification-service | [`EmailIntegrationTest`](miniurl-monolith/src/test/java/com/miniurl/integration/EmailIntegrationTest.java:1) |
| 7 | `RedirectIntegrationTest` | redirect-service | (new — no monolith equivalent for Redis path) |
| 8 | `OutboxIntegrationTest` | identity-service + url-service | (new — outbox pattern is new) |
| 9 | `KafkaIntegrationTest` | notification + analytics | (new — Kafka is new) |
| 10 | `GatewayRoutingIntegrationTest` | api-gateway | (new — gateway is new) |

**Test Infrastructure Requirements:**

1. **Testcontainers** for:
   - MySQL (per service database)
   - Redis (for redirect and feature services)
   - Kafka (for event-driven tests)

2. **GreenMail** for email testing (as used in monolith)

3. **WireMock** for simulating dependent services in integration tests

**Example: AuthenticationIntegrationTest Structure**

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class AuthenticationIntegrationTest {
    
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("identity_test_db");
    
    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.4.0"));
    
    @Autowired
    private MockMvc mockMvc;
    
    @BeforeEach
    void setUp() {
        // Clean database, create test data
    }
    
    @Test
    void shouldSignupWithValidInvitationToken() { ... }
    
    @Test
    void shouldRejectSignupWithoutInvitationToken() { ... }
    
    @Test
    void shouldLoginWithValidCredentials() { ... }
    
    @Test
    void shouldLockAccountAfterFiveFailedAttempts() { ... }
    
    @Test
    void shouldNotEnumerateEmailsOnForgotPassword() { ... }
    
    @Test
    void shouldDeleteOwnAccountOnly() { ... }
}
```

**Required Build Changes:**

Add to each service's `build.gradle` or `pom.xml`:

```groovy
dependencies {
    testImplementation 'org.testcontainers:testcontainers:1.19.3'
    testImplementation 'org.testcontainers:mysql:1.19.3'
    testImplementation 'org.testcontainers:kafka:1.19.3'
    testImplementation 'org.testcontainers:junit-jupiter:1.19.3'
    testImplementation 'com.icegreen:greenmail-junit5:2.0.0'
    testImplementation 'org.springframework.cloud:spring-cloud-contract-wiremock:4.1.0'
}
```

**Acceptance Criteria:**
- [ ] At least 8 integration test classes exist across services
- [ ] Each test class has ≥5 test methods
- [ ] Tests use real databases (Testcontainers), not H2
- [ ] Tests verify service-to-service contracts
- [ ] All tests pass in CI pipeline
- [ ] Test coverage ≥70% for service and controller layers

**Verification Command:**
```bash
# Run all integration tests
./gradlew test --tests "*IntegrationTest" 2>&1 | grep -E "(PASSED|FAILED|tests)"
# Or per service:
cd identity-service && ./gradlew test --tests "*IntegrationTest"
cd url-service && ./gradlew test --tests "*IntegrationTest"
cd redirect-service && ./gradlew test --tests "*IntegrationTest"
cd feature-service && ./gradlew test --tests "*IntegrationTest"
cd notification-service && ./gradlew test --tests "*IntegrationTest"
```

**Estimated Risk if Fixed Incorrectly:**
- **Low** (tests don't affect production). The risk is in tests that are flaky or too slow, causing CI failures and developer frustration.
- **Mitigation:** Use Testcontainers with reuse mode for local development. Set reasonable timeouts. Don't block PRs on flaky tests — quarantine them.

**Implementation Order:** 16th (ongoing throughout all phases — write tests alongside each fix)

---

## Phase 6: Final Production-Readiness Validation

**Goal:** End-to-end validation before declaring GO.
**Services affected:** All
**Risk of not fixing:** Undetected issues in production

---

### Phase 6 Activities

**6.1 End-to-End Flow Validation**

Run the complete user journey:
1. Admin creates email invite
2. User signs up with invite token
3. User receives welcome + registration congrats emails
4. User logs in (with 2FA if enabled)
5. User creates URLs
6. User views paginated URL list
7. Public user redirects via short code
8. Click events recorded in analytics
9. User updates profile
10. User exports data
11. User deletes account with password confirmation
12. Deleted URLs return 404 on redirect
13. Admin views stats, manages users

**6.2 Failure Mode Testing**

| Scenario | Expected Behavior |
|---|---|
| Kill identity service | Gateway returns 503, other services unaffected |
| Kill URL service | Redirect cache hits work, cache misses return 503 |
| Kill Redis | Redirect falls back to URL service (degraded) |
| Kill Kafka | Outbox accumulates, no data loss, emails delayed |
| Kill notification service | Outbox accumulates, emails queued |
| Fill outbox (10k events) | Relay catches up, no OOM |

**6.3 Performance Validation**

```bash
# Redirect load test (target: 10k req/sec sustained)
wrk -t12 -c400 -d60s http://localhost:8080/r/BENCHMARK

# URL creation load test (target: 500 req/sec)
wrk -t4 -c50 -d30s -s post_url.lua http://localhost:8080/api/urls

# Login load test (target: 100 req/sec)
wrk -t4 -c20 -d30s -s post_login.lua http://localhost:8080/api/auth/login
```

**6.4 Security Scan**

```bash
# OWASP ZAP baseline scan
zap-baseline.py -t http://localhost:8080 -r zap-report.html

# Check for exposed actuators
curl -s http://localhost:8080/actuator/env && echo "FAIL: env exposed" || echo "PASS"

# Verify HTTPS redirect (if applicable)
curl -v http://localhost:8080/api/health 2>&1 | grep -i "strict-transport"
```

---

## P0 Fix Checklist

| # | Blocker | Phase | File(s) | Status |
|---|---|---|---|---|
| P0-1 | Open redirect validation | 1 | [`RedirectController.java`](redirect-service/src/main/java/com/miniurl/redirect/controller/RedirectController.java:29) | [ ] |
| P0-2 | Account lockout enforcement | 1 | [`AuthService.java`](identity-service/src/main/java/com/miniurl/identity/service/AuthService.java:515) | [ ] |
| P0-3 | Delete-account JWT extraction | 1 | [`AuthController.java`](identity-service/src/main/java/com/miniurl/identity/controller/AuthController.java:125) | [ ] |
| P0-4 | verify-email wrong method | 1 | [`AuthController.java`](identity-service/src/main/java/com/miniurl/identity/controller/AuthController.java:71) | [ ] |
| P0-5 | Anti-enumeration forgot-password | 1 | [`AuthController.java`](identity-service/src/main/java/com/miniurl/identity/controller/AuthController.java:77) | [ ] |
| P0-14a | Key persistence (mitigation) | 1 | [`KeyService.java`](identity-service/src/main/java/com/miniurl/identity/service/KeyService.java:26) | [ ] |
| P0-6 | AdminController | 2 | `identity-service/.../controller/AdminController.java` (CREATE) | [ ] |
| P0-7 | ProfileController | 2 | `identity-service/.../controller/ProfileController.java` (CREATE) | [ ] |
| P0-8 | SettingsController | 2 | `identity-service/.../controller/SettingsController.java` (CREATE) | [ ] |
| P0-9 | SelfInviteController | 2 | `identity-service/.../controller/SelfInviteController.java` (CREATE) | [ ] |
| P0-10 | Public /api/features/global | 2 | [`FeatureController.java`](feature-service/src/main/java/com/miniurl/feature/controller/FeatureController.java:21) | [ ] |
| P0-11 | /api/features for auth users | 2 | [`FeatureController.java`](feature-service/src/main/java/com/miniurl/feature/controller/FeatureController.java:21) | [ ] |
| P0-12 | REGISTRATION_CONGRATS handler | 3 | [`EmailService.java`](notification-service/src/main/java/com/miniurl/notification/service/EmailService.java:53) | [ ] |
| P0-15 | Cache invalidation on delete | 3 | [`UrlService.java`](url-service/src/main/java/com/miniurl/url/service/UrlService.java:300) | [ ] |
| P0-14b | Key rotation (full fix) | 4 | [`KeyService.java`](identity-service/src/main/java/com/miniurl/identity/service/KeyService.java:26) | [ ] |
| P0-13 | Integration tests | 5 | All services | [ ] |

---

## Regression Test Checklist

Mapped to monolith integration tests. Each must pass against microservices before GO.

| Monolith Test | Microservices Equivalent | Covers P0 |
|---|---|---|
| [`AuthenticationIntegrationTest`](miniurl-monolith/src/test/java/com/miniurl/integration/AuthenticationIntegrationTest.java:1) | identity-service `AuthenticationIntegrationTest` | P0-2, P0-3, P0-4, P0-5 |
| [`UrlCrudIntegrationTest`](miniurl-monolith/src/test/java/com/miniurl/integration/UrlCrudIntegrationTest.java:1) | url-service `UrlCrudIntegrationTest` | P0-15 |
| [`TwoFactorAuthIntegrationTest`](miniurl-monolith/src/test/java/com/miniurl/integration/TwoFactorAuthIntegrationTest.java:1) | identity-service `TwoFactorAuthIntegrationTest` | P0-2 |
| [`FeatureFlagIntegrationTest`](miniurl-monolith/src/test/java/com/miniurl/integration/FeatureFlagIntegrationTest.java:1) | feature-service `FeatureFlagIntegrationTest` | P0-10, P0-11 |
| [`SecurityFeaturesIntegrationTest`](miniurl-monolith/src/test/java/com/miniurl/integration/SecurityFeaturesIntegrationTest.java:1) | identity-service `SecurityFeaturesIntegrationTest` | P0-1, P0-2, P0-3, P0-5 |
| [`EmailIntegrationTest`](miniurl-monolith/src/test/java/com/miniurl/integration/EmailIntegrationTest.java:1) | notification-service `EmailIntegrationTest` | P0-12 |
| [`UserLockoutTest`](miniurl-monolith/src/test/java/com/miniurl/entity/UserLockoutTest.java:1) | identity-service `UserLockoutTest` | P0-2 |
| [`FeatureFlagServiceTest`](miniurl-monolith/src/test/java/com/miniurl/service/FeatureFlagServiceTest.java:1) | feature-service `FeatureFlagServiceTest` | P0-10, P0-11 |
| [`GlobalFlagServiceTest`](miniurl-monolith/src/test/java/com/miniurl/service/GlobalFlagServiceTest.java:1) | feature-service `GlobalFlagServiceTest` | P0-9, P0-10 |

---

## Branch/Commit Breakdown

### Branch Strategy

```
main
  └── fix/phase-1-security-blockers
       ├── commit: fix(p0-1): add open redirect URL validation
       ├── commit: fix(p0-2): enforce account lockout at login
       ├── commit: fix(p0-3): extract user from JWT for delete-account
       ├── commit: fix(p0-4): fix verify-email to call correct method
       ├── commit: fix(p0-5): add anti-enumeration to forgot-password
       └── commit: fix(p0-14a): persist RSA keys to filesystem
  └── fix/phase-2-controller-parity
       ├── commit: feat(p0-6): add AdminController with full CRUD
       ├── commit: feat(p0-7): add ProfileController
       ├── commit: feat(p0-8): add SettingsController
       ├── commit: feat(p0-9): add SelfInviteController
       ├── commit: feat(p0-10): add public /api/features/global endpoint
       └── commit: feat(p0-11): add /api/features for authenticated users
  └── fix/phase-3-event-cache-consistency
       ├── commit: fix(p0-12): handle REGISTRATION_CONGRATS event
       └── commit: fix(p0-15): invalidate cache on URL delete
  └── fix/phase-4-jwt-production
       └── commit: feat(p0-14b): add key rotation with multi-key JWKS
  └── fix/phase-5-integration-tests
       ├── commit: test(p0-13): add identity-service integration tests
       ├── commit: test(p0-13): add url-service integration tests
       ├── commit: test(p0-13): add redirect-service integration tests
       ├── commit: test(p0-13): add feature-service integration tests
       ├── commit: test(p0-13): add notification-service integration tests
       └── commit: test(p0-13): add gateway integration tests
```

### Commit Message Convention

```
<type>(<p0-id>): <description>

- Detailed change 1
- Detailed change 2

Refs: P0-XX
```

Types: `fix` (bug fix), `feat` (new feature), `test` (tests only), `chore` (config/build)

---

## Final GO/NO-GO Gate

### Measurable Criteria

| # | Criterion | Threshold | Measurement |
|---|---|---|---|
| G1 | All P0 blockers resolved | 15/15 | P0 Fix Checklist |
| G2 | All P1 blockers resolved | 12/12 | P1 gap list in audit |
| G3 | Integration tests passing | 100% pass rate | CI pipeline |
| G4 | Unit test coverage (service layer) | ≥70% | JaCoCo report |
| G5 | Redirect latency (p99, warm cache) | ≤10ms | wrk benchmark |
| G6 | Redirect throughput | ≥5,000 req/sec | wrk benchmark |
| G7 | Open redirect protection | 0 vulnerabilities | OWASP ZAP + manual test |
| G8 | Account lockout functional | 5 fails → lockout | Automated test |
| G9 | JWKS endpoint healthy | Returns valid keys | Health check |
| G10 | Key survival across restart | Same kid after restart | Automated test |
| G11 | Cache invalidation on delete | ≤5 seconds | Automated test |
| G12 | All email types delivered | 11/11 types | GreenMail integration test |
| G13 | No silent event loss | 0 dropped events | Outbox relay metrics |
| G14 | Anti-enumeration verified | Identical responses | Automated diff test |
| G15 | Admin endpoints protected | Non-admin → 403 | Automated test |

### GO Decision Matrix

| Condition | Verdict |
|---|---|
| All G1-G15 pass | **GO** — Proceed with production migration |
| G1-G8 pass, G9-G15 have minor issues | **CONDITIONAL GO** — Proceed with documented risks |
| Any G1-G8 fails | **NO-GO** — Must fix before migration |
| Any security criterion (G7, G8, G13, G14, G15) fails | **NO-GO** — Security regression unacceptable |

### Gate Checklist

- [ ] G1: All 15 P0 blockers resolved
- [ ] G2: All 12 P1 items resolved
- [ ] G3: Integration test suite passes (0 failures)
- [ ] G4: Service-layer coverage ≥70%
- [ ] G5: Redirect p99 latency ≤10ms (warm)
- [ ] G6: Redirect throughput ≥5,000/sec
- [ ] G7: Zero open redirect vulnerabilities
- [ ] G8: Account lockout after 5 failed attempts
- [ ] G9: JWKS endpoint returns valid RS256 keys
- [ ] G10: Keys persist across identity-service restart
- [ ] G11: Cache invalidated within 5s of URL delete
- [ ] G12: All 11 email event types delivered
- [ ] G13: Zero silent event loss in outbox relay
- [ ] G14: Forgot-password responses identical for exist/non-exist
- [ ] G15: Admin endpoints return 403 for non-admin users

### Sign-off

| Role | Name | Date | Signature |
|---|---|---|---|
| Lead Engineer | | | |
| Security Reviewer | | | |
| QA Lead | | | |
| DevOps/Platform | | | |
| Product Owner | | | |

---

*End of Migration Remediation Plan*
