# Security Hardening Plan: Information Leakage & OpenAPI Consistency

> **Date**: 2026-05-18  
> **Status**: Draft  
> **Scope**: All microservices (url-service, identity-service, feature-service)

---

## 1. Summary of Findings

A comprehensive audit of all CRUD operations across services identified the following categories of issues:

| # | Category | Severity | Services Affected |
|---|----------|----------|-------------------|
| 1 | Resource existence leakage via differentiated exceptions | Medium | url-service |
| 2 | Missing `GlobalExceptionHandler` for standardized error responses | Medium | url-service, feature-service |
| 3 | Incomplete OpenAPI `@ApiResponses` annotations | Low | url-service |
| 4 | Inconsistent authentication pattern (JWT manual parse vs `@RequestAttribute`) | Low | identity-service |

---

## 2. Detailed Findings

### 2.1 Resource Existence Leakage (url-service)

**Files**: [`url-service/src/main/java/com/miniurl/url/service/UrlService.java`](url-service/src/main/java/com/miniurl/url/service/UrlService.java)

**`getUrlById`** (lines 303–310):
```java
Url url = urlRepository.findByIdAndUserId(urlId, userId)
    .orElseThrow(() -> {
        if (urlRepository.existsById(urlId)) {          // <-- LEAK: reveals URL exists
            return new UnauthorizedException("You can only access your own URLs");
        }
        return new ResourceNotFoundException("URL not found");
    });
```

**`deleteUrl`** (lines 274–281):
```java
Url url = urlRepository.findByIdAndUserId(urlId, userId)
    .orElseThrow(() -> {
        if (urlRepository.existsById(urlId)) {          // <-- LEAK: reveals URL exists
            return new UnauthorizedException("You can only delete your own URLs");
        }
        return new ResourceNotFoundException("URL not found");
    });
```

**Attack vector**: An authenticated user can probe URL IDs to enumerate which IDs exist in the system by observing whether they get a `401 Unauthorized` (URL exists, belongs to someone else) vs `404 Not Found` (URL doesn't exist).

**Contrast with good practice in AuthController**: The [`login`](identity-service/src/main/java/com/miniurl/identity/controller/AuthController.java:142) endpoint already implements proper anti-enumeration — always returns `"Invalid credentials"` regardless of whether the user exists, password is wrong, or account is rate-limited. It even simulates a password hash check for non-existent users to prevent timing attacks.

### 2.2 Missing GlobalExceptionHandler

| Service | Has GlobalExceptionHandler? | Consequence |
|---------|----------------------------|-------------|
| identity-service | ✅ Yes ([`GlobalExceptionHandler`](identity-service/src/main/java/com/miniurl/identity/config/GlobalExceptionHandler.java)) | `ResourceNotFoundException` → 404, `UnauthorizedException` → 401 |
| url-service | ❌ No | Exceptions fall through to default Spring handling; inconsistent error format |
| feature-service | ❌ No | Same — no standardized error responses |

The identity-service handler maps:
- `ResourceNotFoundException` → `404 NOT_FOUND` with `ApiResponse.error(message)`
- `UnauthorizedException` → `401 UNAUTHORIZED` with `ApiResponse.error(message)`

url-service and feature-service should have equivalent handlers for consistency.

### 2.3 Incomplete OpenAPI Annotations (url-service)

**File**: [`url-service/src/main/java/com/miniurl/url/controller/UrlController.java`](url-service/src/main/java/com/miniurl/url/controller/UrlController.java)

| Endpoint | Currently Documents | Missing |
|----------|--------------------|---------|
| `POST /api/urls` (createUrl) | 200, 400 | 401 (unauthenticated) |
| `GET /api/urls` (getUserUrls) | 200 | 401 |
| `GET /api/urls/paged` (getUserUrlsPaged) | 200 | 401 |
| `GET /api/urls/{id}` (getUrlById) | 200, 404 | 401, 403 |
| `DELETE /api/urls/{id}` (deleteUrl) | 200, 404 | 401, 403 |
| `GET /api/urls/usage-stats` (getUsageStats) | 200 | 401 |

**Contrast**: Identity-service controllers ([`ProfileController`](identity-service/src/main/java/com/miniurl/identity/controller/ProfileController.java), [`SettingsController`](identity-service/src/main/java/com/miniurl/identity/controller/SettingsController.java), [`FeatureFlagPublicController`](identity-service/src/main/java/com/miniurl/identity/controller/FeatureFlagPublicController.java)) already document `401` and `404` responses properly.

### 2.4 Inconsistent Authentication Pattern (identity-service)

Identity-service controllers manually parse the JWT from the `Authorization` header:
```java
// Pattern used in ProfileController, SettingsController, FeatureFlagPublicController, AuthController.deleteAccount
String token = authHeader.substring(7);
String username = jwtService.extractUsername(token);
User user = userRepository.findByUsername(username)
    .orElseThrow(() -> new ResourceNotFoundException("User not found"));
```

While url-service uses the cleaner `@RequestAttribute("userId")` pattern (set by an auth filter/interceptor). The identity-service pattern is functionally correct but:
- Duplicates JWT parsing logic across 4+ controllers
- Requires a DB lookup by username on every request
- Is more verbose and error-prone

---

## 3. Implementation Plan

### Phase 1: Fix Information Leakage in UrlService (P0)

**Files to change**:
- [`url-service/src/main/java/com/miniurl/url/service/UrlService.java`](url-service/src/main/java/com/miniurl/url/service/UrlService.java)

**Changes**:

**`getUrlById`** (line 303): Remove the `existsById` check. Always return `ResourceNotFoundException`:
```java
public UrlResponse getUrlById(Long urlId, Long userId) {
    Url url = urlRepository.findByIdAndUserId(urlId, userId)
        .orElseThrow(() -> new ResourceNotFoundException("URL not found"));
    return convertToResponse(url);
}
```

**`deleteUrl`** (line 274): Same treatment:
```java
@Transactional
public void deleteUrl(Long urlId, Long userId) {
    Url url = urlRepository.findByIdAndUserId(urlId, userId)
        .orElseThrow(() -> new ResourceNotFoundException("URL not found"));
    // ... rest of delete logic unchanged
}
```

**Note**: The `UnauthorizedException` import can be removed from UrlService if no longer used elsewhere in the file.

### Phase 2: Add GlobalExceptionHandler to url-service and feature-service (P1)

#### 2a. url-service

**New file**: `url-service/src/main/java/com/miniurl/url/config/GlobalExceptionHandler.java`

```java
package com.miniurl.url.config;

import com.miniurl.dto.ApiResponse;
import com.miniurl.exception.AliasNotAvailableException;
import com.miniurl.exception.ResourceNotFoundException;
import com.miniurl.exception.UnauthorizedException;
import com.miniurl.exception.UrlValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(errors));
    }

    @ExceptionHandler(UrlValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleUrlValidation(UrlValidationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(AliasNotAvailableException.class)
    public ResponseEntity<ApiResponse<Void>> handleAliasNotAvailable(AliasNotAvailableException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleResourceNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnauthorized(UnauthorizedException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneral(Exception ex) {
        log.error("Unhandled exception: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("An unexpected error occurred"));
    }
}
```

#### 2b. feature-service

**New file**: `feature-service/src/main/java/com/miniurl/feature/config/GlobalExceptionHandler.java`

```java
package com.miniurl.feature.config;

import com.miniurl.dto.ApiResponse;
import com.miniurl.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleResourceNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneral(Exception ex) {
        log.error("Unhandled exception: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("An unexpected error occurred"));
    }
}
```

### Phase 3: Fix OpenAPI Annotations in UrlController (P2)

**File**: [`url-service/src/main/java/com/miniurl/url/controller/UrlController.java`](url-service/src/main/java/com/miniurl/url/controller/UrlController.java)

Each endpoint's `@ApiResponses` should be updated:

| Endpoint | Current | Updated |
|----------|---------|---------|
| `createUrl` | `200, 400` | `200, 400, 401` |
| `getUserUrls` | `200` | `200, 401` |
| `getUserUrlsPaged` | `200` | `200, 401` |
| `getUrlById` | `200, 404` | `200, 401, 404` |
| `deleteUrl` | `200, 404` | `200, 401, 404` |
| `getUsageStats` | `200` | `200, 401` |

Example for `getUrlById`:
```java
@Operation(summary = "Get URL by ID", description = "Returns a single URL by its ID. Only accessible by the URL owner.")
@ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "URL retrieved successfully"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "URL not found")
})
```

### Phase 4: (Optional) Unify Authentication Pattern in identity-service (P3)

This is a lower-priority refactoring. The identity-service controllers could be migrated to use `@RequestAttribute("userId")` like url-service, which would require:

1. An auth filter/interceptor in identity-service that validates the JWT and sets `userId` as a request attribute
2. Updating [`ProfileController`](identity-service/src/main/java/com/miniurl/identity/controller/ProfileController.java), [`SettingsController`](identity-service/src/main/java/com/miniurl/identity/controller/SettingsController.java), [`FeatureFlagPublicController`](identity-service/src/main/java/com/miniurl/identity/controller/FeatureFlagPublicController.java), and [`AuthController.deleteAccount`](identity-service/src/main/java/com/miniurl/identity/controller/AuthController.java:261) to use `@RequestAttribute("userId")` instead of manual JWT parsing

**Benefit**: Eliminates ~20 lines of duplicated JWT parsing code, removes the extra DB lookup by username, and provides consistency across services.

---

## 4. Files Summary

| Action | File | Phase |
|--------|------|-------|
| MODIFY | `url-service/src/main/java/com/miniurl/url/service/UrlService.java` | P0 |
| CREATE | `url-service/src/main/java/com/miniurl/url/config/GlobalExceptionHandler.java` | P1 |
| CREATE | `feature-service/src/main/java/com/miniurl/feature/config/GlobalExceptionHandler.java` | P1 |
| MODIFY | `url-service/src/main/java/com/miniurl/url/controller/UrlController.java` | P2 |
| MODIFY | `identity-service/src/main/java/com/miniurl/identity/controller/ProfileController.java` | P3 (optional) |
| MODIFY | `identity-service/src/main/java/com/miniurl/identity/controller/SettingsController.java` | P3 (optional) |
| MODIFY | `identity-service/src/main/java/com/miniurl/identity/controller/FeatureFlagPublicController.java` | P3 (optional) |
| MODIFY | `identity-service/src/main/java/com/miniurl/identity/controller/AuthController.java` | P3 (optional) |

---

## 5. Verification Checklist

- [ ] `getUrlById` with another user's URL ID returns `404` (not `401`)
- [ ] `deleteUrl` with another user's URL ID returns `404` (not `401`)
- [ ] `getUrlById` with own URL ID still returns `200`
- [ ] `deleteUrl` with own URL ID still returns `200`
- [ ] url-service error responses use `ApiResponse` format consistently
- [ ] feature-service error responses use `ApiResponse` format consistently
- [ ] OpenAPI/Swagger UI shows correct response codes for all UrlController endpoints
- [ ] Existing tests pass (update any tests that assert on `UnauthorizedException` for cross-user access)
