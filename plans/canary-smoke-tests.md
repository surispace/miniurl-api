# CANARY SMOKE TESTS — MiniURL Microservices

**Usage:** Run after each canary phase (5%, 25%, 50%, 100%).
**Prerequisites:** Replace `<INGRESS_HOST>`, `<REDIRECT_HOST>`, `<ADMIN_PASSWORD>`, `<TEST_EMAIL>` with actual values.

---

## Environment Setup

```bash
# Set these variables before running tests
export NAMESPACE="<NAMESPACE>"                 # Kubernetes namespace (e.g., miniurl-prod)
export INGRESS_HOST="<INGRESS_HOST>"           # e.g., api.miniurl.example.com
export REDIRECT_HOST="<REDIRECT_HOST>"         # e.g., r.miniurl.example.com
export ADMIN_USERNAME="admin"
export ADMIN_PASSWORD="<ADMIN_PASSWORD>"
export TEST_EMAIL="<TEST_EMAIL>"               # e.g., canary-test@example.com
export TEST_USERNAME="canarytestuser"
export TEST_PASSWORD="CanaryTest123!"
export TEST_FIRST_NAME="Canary"
export TEST_LAST_NAME="Test"

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

pass() { echo -e "${GREEN}PASS${NC}: $1"; }
fail() { echo -e "${RED}FAIL${NC}: $1"; }
warn() { echo -e "${YELLOW}WARN${NC}: $1"; }
```

---

## 1. HEALTH CHECKS

### 1.1 API Gateway Health

```bash
echo "=== 1.1 API Gateway Health ==="
RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" https://$INGRESS_HOST/actuator/health)
if [ "$RESPONSE" = "200" ]; then
  pass "API Gateway health returned 200"
else
  fail "API Gateway health returned $RESPONSE"
fi
```

### 1.2 Redirect Service Health

```bash
echo "=== 1.2 Redirect Service Health ==="
RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" https://$REDIRECT_HOST/actuator/health)
if [ "$RESPONSE" = "200" ]; then
  pass "Redirect Service health returned 200"
else
  fail "Redirect Service health returned $RESPONSE"
fi
```

### 1.3 JWKS Endpoint

```bash
echo "=== 1.3 JWKS Endpoint ==="
JWKS=$(curl -s https://$INGRESS_HOST/.well-known/jwks.json)
KTY=$(echo "$JWKS" | jq -r '.keys[0].kty')
KID=$(echo "$JWKS" | jq -r '.keys[0].kid')
if [ "$KTY" = "RSA" ] && [ -n "$KID" ]; then
  pass "JWKS endpoint returns RSA key (kid=$KID)"
else
  fail "JWKS endpoint invalid: kty=$KTY, kid=$KID"
fi
```

---

## 2. AUTHENTICATION FLOWS

### 2.1 Admin Login (OTP Flow)

```bash
echo "=== 2.1 Admin Login ==="
# Step 1: Login with credentials
LOGIN_RESPONSE=$(curl -s -X POST https://$INGRESS_HOST/api/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"username\": \"$ADMIN_USERNAME\", \"password\": \"$ADMIN_PASSWORD\"}")

LOGIN_SUCCESS=$(echo "$LOGIN_RESPONSE" | jq -r '.success')
LOGIN_MESSAGE=$(echo "$LOGIN_RESPONSE" | jq -r '.message')

if [ "$LOGIN_SUCCESS" = "true" ]; then
  pass "Admin login accepted: $LOGIN_MESSAGE"
else
  fail "Admin login rejected: $LOGIN_MESSAGE"
  echo "$LOGIN_RESPONSE" | jq .
fi

# Step 2: Get OTP from database (in production, check email)
# NOTE: This requires kubectl access to the cluster from the test runner.
# If running from outside the cluster, ensure kubeconfig is configured.
echo "Retrieving OTP from database..."
OTP=$(kubectl exec -n $NAMESPACE deploy/mysql-identity -- mysql -u root -p$MYSQL_ROOT_PASSWORD identity_db -N -e \
  "SELECT otp_code FROM users WHERE username='$ADMIN_USERNAME';" 2>/dev/null)

if [ -z "$OTP" ] || [ "$OTP" = "NULL" ]; then
  fail "Could not retrieve OTP for admin user"
else
  pass "OTP retrieved: $OTP"

  # Step 3: Verify OTP
  VERIFY_RESPONSE=$(curl -s -X POST https://$INGRESS_HOST/api/auth/verify-otp \
    -H "Content-Type: application/json" \
    -d "{\"username\": \"$ADMIN_USERNAME\", \"otp\": \"$OTP\"}")

  VERIFY_SUCCESS=$(echo "$VERIFY_RESPONSE" | jq -r '.success')
  ADMIN_TOKEN=$(echo "$VERIFY_RESPONSE" | jq -r '.data.token')

  if [ "$VERIFY_SUCCESS" = "true" ] && [ -n "$ADMIN_TOKEN" ] && [ "$ADMIN_TOKEN" != "null" ]; then
    pass "Admin OTP verified, JWT obtained"
    echo "ADMIN_TOKEN=$ADMIN_TOKEN"
  else
    fail "Admin OTP verification failed"
    echo "$VERIFY_RESPONSE" | jq .
  fi
fi
```

### 2.2 Signup New User

```bash
echo "=== 2.2 Signup New User ==="
# First, enable global signup if disabled
# (requires admin token from 2.1)
# curl -s -X PATCH "https://$INGRESS_HOST/api/global-flags/1/toggle?enabled=true" \
#   -H "Authorization: Bearer $ADMIN_TOKEN"

SIGNUP_RESPONSE=$(curl -s -X POST https://$INGRESS_HOST/api/auth/signup \
  -H "Content-Type: application/json" \
  -d "{
    \"firstName\": \"$TEST_FIRST_NAME\",
    \"lastName\": \"$TEST_LAST_NAME\",
    \"username\": \"$TEST_USERNAME\",
    \"password\": \"$TEST_PASSWORD\"
  }")

SIGNUP_SUCCESS=$(echo "$SIGNUP_RESPONSE" | jq -r '.success')
SIGNUP_MESSAGE=$(echo "$SIGNUP_RESPONSE" | jq -r '.message')

if [ "$SIGNUP_SUCCESS" = "true" ]; then
  USER_TOKEN=$(echo "$SIGNUP_RESPONSE" | jq -r '.data.token')
  USER_ID=$(echo "$SIGNUP_RESPONSE" | jq -r '.data.userId')
  pass "User signup successful (userId=$USER_ID)"
  echo "USER_TOKEN=$USER_TOKEN"
  echo "USER_ID=$USER_ID"
else
  warn "User signup: $SIGNUP_MESSAGE (may already exist)"
fi
```

### 2.3 User Login Flow

```bash
echo "=== 2.3 User Login ==="
LOGIN_RESPONSE=$(curl -s -X POST https://$INGRESS_HOST/api/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"username\": \"$TEST_USERNAME\", \"password\": \"$TEST_PASSWORD\"}")

LOGIN_SUCCESS=$(echo "$LOGIN_RESPONSE" | jq -r '.success')

if [ "$LOGIN_SUCCESS" = "true" ]; then
  pass "User login accepted (OTP sent)"

  # Get OTP
  OTP=$(kubectl exec -n <NAMESPACE> deploy/mysql-identity -- mysql -u root -p$MYSQL_ROOT_PASSWORD identity_db -N -e \
    "SELECT otp_code FROM users WHERE username='$TEST_USERNAME';" 2>/dev/null)

  if [ -n "$OTP" ] && [ "$OTP" != "NULL" ]; then
    VERIFY_RESPONSE=$(curl -s -X POST https://$INGRESS_HOST/api/auth/verify-otp \
      -H "Content-Type: application/json" \
      -d "{\"username\": \"$TEST_USERNAME\", \"otp\": \"$OTP\"}")

    VERIFY_SUCCESS=$(echo "$VERIFY_RESPONSE" | jq -r '.success')
    USER_TOKEN=$(echo "$VERIFY_RESPONSE" | jq -r '.data.token')

    if [ "$VERIFY_SUCCESS" = "true" ]; then
      pass "User OTP verified, JWT obtained"
      echo "USER_TOKEN=$USER_TOKEN"
    else
      fail "User OTP verification failed"
    fi
  fi
else
  fail "User login rejected"
  echo "$LOGIN_RESPONSE" | jq .
fi
```

---

## 3. JWT VALIDATION

### 3.1 Protected Endpoint with Valid Token

```bash
echo "=== 3.1 Protected Endpoint (Profile) ==="
PROFILE_RESPONSE=$(curl -s https://$INGRESS_HOST/api/profile \
  -H "Authorization: Bearer $USER_TOKEN")

PROFILE_SUCCESS=$(echo "$PROFILE_RESPONSE" | jq -r '.success')

if [ "$PROFILE_SUCCESS" = "true" ]; then
  pass "Profile endpoint accessible with valid JWT"
else
  fail "Profile endpoint rejected valid JWT"
  echo "$PROFILE_RESPONSE" | jq .
fi
```

### 3.2 Protected Endpoint with Invalid Token

```bash
echo "=== 3.2 Protected Endpoint (Invalid Token) ==="
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" https://$INGRESS_HOST/api/profile \
  -H "Authorization: Bearer invalid.jwt.token")

if [ "$HTTP_CODE" = "401" ] || [ "$HTTP_CODE" = "403" ]; then
  pass "Invalid JWT correctly rejected (HTTP $HTTP_CODE)"
else
  fail "Invalid JWT returned HTTP $HTTP_CODE (expected 401 or 403)"
fi
```

### 3.3 Protected Endpoint with No Token

```bash
echo "=== 3.3 Protected Endpoint (No Token) ==="
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" https://$INGRESS_HOST/api/profile)

if [ "$HTTP_CODE" = "401" ] || [ "$HTTP_CODE" = "403" ]; then
  pass "Missing JWT correctly rejected (HTTP $HTTP_CODE)"
else
  fail "Missing JWT returned HTTP $HTTP_CODE (expected 401 or 403)"
fi
```

---

## 4. URL MANAGEMENT

### 4.1 Create URL

```bash
echo "=== 4.1 Create URL ==="
CREATE_RESPONSE=$(curl -s -X POST https://$INGRESS_HOST/api/urls \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $USER_TOKEN" \
  -d "{\"originalUrl\": \"https://example.com/canary-test-$(date +%s)\"}")

CREATE_SUCCESS=$(echo "$CREATE_RESPONSE" | jq -r '.success')
SHORT_CODE=$(echo "$CREATE_RESPONSE" | jq -r '.data.shortCode')
URL_ID=$(echo "$CREATE_RESPONSE" | jq -r '.data.id')

if [ "$CREATE_SUCCESS" = "true" ] && [ -n "$SHORT_CODE" ] && [ "$SHORT_CODE" != "null" ]; then
  pass "URL created: shortCode=$SHORT_CODE, id=$URL_ID"
  echo "SHORT_CODE=$SHORT_CODE"
  echo "URL_ID=$URL_ID"
else
  fail "URL creation failed"
  echo "$CREATE_RESPONSE" | jq .
fi
```

### 4.2 Redirect URL (Follow Redirect)

```bash
echo "=== 4.2 Redirect URL ==="
REDIRECT_HTTP=$(curl -s -o /dev/null -w "%{http_code}" -L https://$REDIRECT_HOST/r/$SHORT_CODE)

if [ "$REDIRECT_HTTP" = "200" ]; then
  pass "Redirect followed successfully (HTTP 200)"
else
  fail "Redirect returned HTTP $REDIRECT_HTTP"
fi
```

### 4.3 Redirect URL (Check Location Header)

```bash
echo "=== 4.3 Redirect Location Header ==="
LOCATION=$(curl -s -I -o /dev/null -w "%{redirect_url}" https://$REDIRECT_HOST/r/$SHORT_CODE)

if [ -n "$LOCATION" ] && [[ "$LOCATION" == https://example.com/* ]]; then
  pass "Redirect Location header correct: $LOCATION"
else
  fail "Redirect Location header invalid: $LOCATION"
fi
```

### 4.4 Redirect Non-Existent Code

```bash
echo "=== 4.4 Redirect Non-Existent Code ==="
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" https://$REDIRECT_HOST/r/nonexistent123)

if [ "$HTTP_CODE" = "404" ]; then
  pass "Non-existent short code returns 404"
else
  fail "Non-existent short code returned HTTP $HTTP_CODE (expected 404)"
fi
```

### 4.5 Get User URLs

```bash
echo "=== 4.5 Get User URLs ==="
URLS_RESPONSE=$(curl -s https://$INGRESS_HOST/api/urls \
  -H "Authorization: Bearer $USER_TOKEN")

URLS_SUCCESS=$(echo "$URLS_RESPONSE" | jq -r '.success')
URL_COUNT=$(echo "$URLS_RESPONSE" | jq -r '.data | length')

if [ "$URLS_SUCCESS" = "true" ] && [ "$URL_COUNT" -gt 0 ]; then
  pass "User URLs retrieved: $URL_COUNT URLs"
else
  warn "User URLs: count=$URL_COUNT"
fi
```

### 4.6 Delete URL

```bash
echo "=== 4.6 Delete URL ==="
DELETE_RESPONSE=$(curl -s -X DELETE https://$INGRESS_HOST/api/urls/$URL_ID \
  -H "Authorization: Bearer $USER_TOKEN")

DELETE_SUCCESS=$(echo "$DELETE_RESPONSE" | jq -r '.success')

if [ "$DELETE_SUCCESS" = "true" ]; then
  pass "URL deleted successfully (id=$URL_ID)"
else
  fail "URL deletion failed"
  echo "$DELETE_RESPONSE" | jq .
fi
```

### 4.7 Verify Redirect No Longer Works After Delete

```bash
echo "=== 4.7 Redirect After Delete ==="
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" https://$REDIRECT_HOST/r/$SHORT_CODE)

if [ "$HTTP_CODE" = "404" ]; then
  pass "Deleted URL correctly returns 404 on redirect"
else
  fail "Deleted URL returned HTTP $HTTP_CODE (expected 404) — cache may not be invalidated"
fi
```

---

## 5. SECURITY EDGE CASES

### 5.1 Open Redirect Protection — javascript: Protocol

```bash
echo "=== 5.1 Open Redirect (javascript:) ==="
# Create a URL with javascript: protocol (should be rejected at creation)
CREATE_RESPONSE=$(curl -s -X POST https://$INGRESS_HOST/api/urls \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $USER_TOKEN" \
  -d '{"originalUrl": "javascript:alert(1)"}')

CREATE_SUCCESS=$(echo "$CREATE_RESPONSE" | jq -r '.success')

if [ "$CREATE_SUCCESS" = "false" ]; then
  pass "javascript: URL correctly rejected at creation"
else
  fail "javascript: URL was accepted — SSRF/validation gap!"
fi
```

### 5.2 Open Redirect Protection — data: Protocol

```bash
echo "=== 5.2 Open Redirect (data:) ==="
CREATE_RESPONSE=$(curl -s -X POST https://$INGRESS_HOST/api/urls \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $USER_TOKEN" \
  -d '{"originalUrl": "data:text/html,<script>alert(1)</script>"}')

CREATE_SUCCESS=$(echo "$CREATE_RESPONSE" | jq -r '.success')

if [ "$CREATE_SUCCESS" = "false" ]; then
  pass "data: URL correctly rejected at creation"
else
  fail "data: URL was accepted — SSRF/validation gap!"
fi
```

### 5.3 SSRF Protection — Internal IP

```bash
echo "=== 5.3 SSRF Protection (Internal IP) ==="
CREATE_RESPONSE=$(curl -s -X POST https://$INGRESS_HOST/api/urls \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $USER_TOKEN" \
  -d '{"originalUrl": "http://127.0.0.1:8080/admin"}')

CREATE_SUCCESS=$(echo "$CREATE_RESPONSE" | jq -r '.success')

if [ "$CREATE_SUCCESS" = "false" ]; then
  pass "Internal IP URL correctly rejected (SSRF protection)"
else
  fail "Internal IP URL was accepted — SSRF gap!"
fi
```

### 5.4 SSRF Protection — Cloud Metadata

```bash
echo "=== 5.4 SSRF Protection (Cloud Metadata) ==="
CREATE_RESPONSE=$(curl -s -X POST https://$INGRESS_HOST/api/urls \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $USER_TOKEN" \
  -d '{"originalUrl": "http://169.254.169.254/latest/meta-data/"}')

CREATE_SUCCESS=$(echo "$CREATE_RESPONSE" | jq -r '.success')

if [ "$CREATE_SUCCESS" = "false" ]; then
  pass "Cloud metadata URL correctly rejected (SSRF protection)"
else
  fail "Cloud metadata URL was accepted — SSRF gap!"
fi
```

### 5.5 Self-Referencing URL

```bash
echo "=== 5.5 Self-Referencing URL ==="
CREATE_RESPONSE=$(curl -s -X POST https://$INGRESS_HOST/api/urls \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $USER_TOKEN" \
  -d "{\"originalUrl\": \"https://$INGRESS_HOST/r/abc123\"}")

CREATE_SUCCESS=$(echo "$CREATE_RESPONSE" | jq -r '.success')

if [ "$CREATE_SUCCESS" = "false" ]; then
  pass "Self-referencing URL correctly rejected"
else
  warn "Self-referencing URL was accepted (may be intentional)"
fi
```

---

## 6. FORGOT PASSWORD — ANTI-ENUMERATION

### 6.1 Forgot Password — Existing User

```bash
echo "=== 6.1 Forgot Password (Existing User) ==="
FORGOT_RESPONSE=$(curl -s -X POST https://$INGRESS_HOST/api/auth/forgot-password \
  -H "Content-Type: application/json" \
  -d "{\"email\": \"$TEST_EMAIL\"}")

FORGOT_SUCCESS=$(echo "$FORGOT_RESPONSE" | jq -r '.success')
FORGOT_MESSAGE=$(echo "$FORGOT_RESPONSE" | jq -r '.message')

if [ "$FORGOT_SUCCESS" = "true" ]; then
  pass "Forgot password for existing user: $FORGOT_MESSAGE"
else
  fail "Forgot password failed: $FORGOT_MESSAGE"
fi
```

### 6.2 Forgot Password — Non-Existent User (Anti-Enumeration)

```bash
echo "=== 6.2 Forgot Password (Non-Existent User) ==="
FORGOT_RESPONSE=$(curl -s -X POST https://$INGRESS_HOST/api/auth/forgot-password \
  -H "Content-Type: application/json" \
  -d '{"email": "nonexistent-user-99999@example.com"}')

FORGOT_SUCCESS=$(echo "$FORGOT_RESPONSE" | jq -r '.success')
FORGOT_MESSAGE=$(echo "$FORGOT_RESPONSE" | jq -r '.message')

# Anti-enumeration: should return same success message as existing user
if [ "$FORGOT_SUCCESS" = "true" ]; then
  pass "Anti-enumeration: non-existent user gets same success response"
else
  fail "Anti-enumeration gap: non-existent user got different response: $FORGOT_MESSAGE"
fi
```

---

## 7. ADMIN ENDPOINTS

### 7.1 Admin Stats (Authorized)

```bash
echo "=== 7.1 Admin Stats (Authorized) ==="
STATS_RESPONSE=$(curl -s https://$INGRESS_HOST/api/admin/stats \
  -H "Authorization: Bearer $ADMIN_TOKEN")

STATS_SUCCESS=$(echo "$STATS_RESPONSE" | jq -r '.success')

if [ "$STATS_SUCCESS" = "true" ]; then
  TOTAL=$(echo "$STATS_RESPONSE" | jq -r '.data.totalUsers')
  pass "Admin stats accessible: totalUsers=$TOTAL"
else
  fail "Admin stats rejected for admin user"
  echo "$STATS_RESPONSE" | jq .
fi
```

### 7.2 Admin Endpoint (Unauthorized — Regular User)

```bash
echo "=== 7.2 Admin Endpoint (Regular User) ==="
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" https://$INGRESS_HOST/api/admin/stats \
  -H "Authorization: Bearer $USER_TOKEN")

if [ "$HTTP_CODE" = "403" ]; then
  pass "Admin endpoint correctly returns 403 for regular user"
else
  fail "Admin endpoint returned HTTP $HTTP_CODE for regular user (expected 403)"
fi
```

### 7.3 Admin Endpoint (No Auth)

```bash
echo "=== 7.3 Admin Endpoint (No Auth) ==="
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" https://$INGRESS_HOST/api/admin/stats)

if [ "$HTTP_CODE" = "401" ] || [ "$HTTP_CODE" = "403" ]; then
  pass "Admin endpoint correctly rejects unauthenticated request (HTTP $HTTP_CODE)"
else
  fail "Admin endpoint returned HTTP $HTTP_CODE without auth (expected 401/403)"
fi
```

### 7.4 Admin User List

```bash
echo "=== 7.4 Admin User List ==="
USERS_RESPONSE=$(curl -s "https://$INGRESS_HOST/api/admin/users?page=0&size=5" \
  -H "Authorization: Bearer $ADMIN_TOKEN")

USERS_SUCCESS=$(echo "$USERS_RESPONSE" | jq -r '.success')

if [ "$USERS_SUCCESS" = "true" ]; then
  USER_COUNT=$(echo "$USERS_RESPONSE" | jq -r '.data.pagination.totalElements')
  pass "Admin user list accessible: $USER_COUNT total users"
else
  fail "Admin user list failed"
fi
```

---

## 8. FEATURE FLAG ENDPOINTS

### 8.1 Public Global Flags (No Auth)

```bash
echo "=== 8.1 Public Global Flags ==="
GLOBAL_RESPONSE=$(curl -s https://$INGRESS_HOST/api/features/global)

# This endpoint may return data directly or wrapped in ApiResponse
GLOBAL_COUNT=$(echo "$GLOBAL_RESPONSE" | jq '.data | length' 2>/dev/null)
if [ -z "$GLOBAL_COUNT" ] || [ "$GLOBAL_COUNT" = "null" ]; then
  GLOBAL_COUNT=$(echo "$GLOBAL_RESPONSE" | jq 'length')
fi

if [ "$GLOBAL_COUNT" -gt 0 ] 2>/dev/null; then
  pass "Public global flags accessible: $GLOBAL_COUNT flags"
else
  warn "Public global flags returned $GLOBAL_COUNT items"
fi
```

### 8.2 User Role Features (Authenticated)

```bash
echo "=== 8.2 User Role Features ==="
FEATURES_RESPONSE=$(curl -s https://$INGRESS_HOST/api/features \
  -H "Authorization: Bearer $USER_TOKEN")

FEATURE_COUNT=$(echo "$FEATURES_RESPONSE" | jq '.data | length' 2>/dev/null)
if [ -z "$FEATURE_COUNT" ] || [ "$FEATURE_COUNT" = "null" ]; then
  FEATURE_COUNT=$(echo "$FEATURES_RESPONSE" | jq 'length')
fi

if [ "$FEATURE_COUNT" -gt 0 ] 2>/dev/null; then
  pass "User role features accessible: $FEATURE_COUNT features"
else
  warn "User role features returned $FEATURE_COUNT items"
fi
```

### 8.3 Admin Global Flag Management

```bash
echo "=== 8.3 Admin Global Flag Management ==="
# List all global flags
FLAGS_RESPONSE=$(curl -s https://$INGRESS_HOST/api/global-flags \
  -H "Authorization: Bearer $ADMIN_TOKEN")

FLAG_COUNT=$(echo "$FLAGS_RESPONSE" | jq 'length')

if [ "$FLAG_COUNT" -gt 0 ] 2>/dev/null; then
  pass "Admin global flags accessible: $FLAG_COUNT flags"
else
  warn "Admin global flags returned $FLAG_COUNT items"
fi
```

### 8.4 Toggle Global Flag

```bash
echo "=== 8.4 Toggle Global Flag ==="
# Get first global flag ID
FLAG_ID=$(echo "$FLAGS_RESPONSE" | jq -r '.[0].id')

if [ -n "$FLAG_ID" ] && [ "$FLAG_ID" != "null" ]; then
  TOGGLE_RESPONSE=$(curl -s -X PATCH "https://$INGRESS_HOST/api/global-flags/$FLAG_ID/toggle?enabled=true" \
    -H "Authorization: Bearer $ADMIN_TOKEN")

  TOGGLE_ENABLED=$(echo "$TOGGLE_RESPONSE" | jq -r '.enabled')

  if [ "$TOGGLE_ENABLED" = "true" ]; then
    pass "Global flag $FLAG_ID toggled successfully"
  else
    fail "Global flag toggle failed"
  fi
else
  warn "No global flags found to toggle"
fi
```

---

## 9. SELF-INVITE ENDPOINT

### 9.1 Self-Invite (Authenticated)

```bash
echo "=== 9.1 Self-Invite ==="
INVITE_RESPONSE=$(curl -s -X POST "https://$INGRESS_HOST/api/self-invite/send?email=$TEST_EMAIL" \
  -H "Authorization: Bearer $USER_TOKEN")

INVITE_SUCCESS=$(echo "$INVITE_RESPONSE" | jq -r '.success')
INVITE_MESSAGE=$(echo "$INVITE_RESPONSE" | jq -r '.message')

if [ "$INVITE_SUCCESS" = "true" ]; then
  pass "Self-invite sent: $INVITE_MESSAGE"
else
  warn "Self-invite: $INVITE_MESSAGE (may be disabled by global flag)"
fi
```

---

## 10. PROFILE & SETTINGS

### 10.1 Get Profile

```bash
echo "=== 10.1 Get Profile ==="
PROFILE_RESPONSE=$(curl -s https://$INGRESS_HOST/api/profile \
  -H "Authorization: Bearer $USER_TOKEN")

PROFILE_SUCCESS=$(echo "$PROFILE_RESPONSE" | jq -r '.success')

if [ "$PROFILE_SUCCESS" = "true" ]; then
  USERNAME=$(echo "$PROFILE_RESPONSE" | jq -r '.data.username')
  pass "Profile retrieved for user: $USERNAME"
else
  fail "Profile retrieval failed"
fi
```

### 10.2 Export Data

```bash
echo "=== 10.2 Export Data ==="
EXPORT_RESPONSE=$(curl -s https://$INGRESS_HOST/api/settings/export \
  -H "Authorization: Bearer $USER_TOKEN")

EXPORT_SUCCESS=$(echo "$EXPORT_RESPONSE" | jq -r '.success')

if [ "$EXPORT_SUCCESS" = "true" ]; then
  pass "Data export successful"
else
  warn "Data export: $(echo $EXPORT_RESPONSE | jq -r '.message')"
fi
```

---

## 11. DELETE ACCOUNT

### 11.1 Delete Account

```bash
echo "=== 11.1 Delete Account ==="
DELETE_ACCT_RESPONSE=$(curl -s -X POST https://$INGRESS_HOST/api/auth/delete-account \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $USER_TOKEN" \
  -d "{\"password\": \"$TEST_PASSWORD\"}")

DELETE_SUCCESS=$(echo "$DELETE_ACCT_RESPONSE" | jq -r '.success')

if [ "$DELETE_SUCCESS" = "true" ]; then
  pass "Account deleted successfully"
else
  warn "Account deletion: $(echo $DELETE_ACCT_RESPONSE | jq -r '.message')"
fi
```

### 11.2 Verify Deleted Account Cannot Login

```bash
echo "=== 11.2 Deleted Account Login ==="
LOGIN_RESPONSE=$(curl -s -X POST https://$INGRESS_HOST/api/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"username\": \"$TEST_USERNAME\", \"password\": \"$TEST_PASSWORD\"}")

LOGIN_SUCCESS=$(echo "$LOGIN_RESPONSE" | jq -r '.success')

if [ "$LOGIN_SUCCESS" = "false" ]; then
  pass "Deleted account correctly rejected at login"
else
  fail "Deleted account was able to login!"
fi
```

---

## 12. RUN ALL TESTS

To run the complete smoke test suite:

```bash
#!/bin/bash
# Save as run-smoke-tests.sh and execute after setting environment variables

echo "========================================="
echo "  MiniURL Canary Smoke Tests"
echo "  Target: $INGRESS_HOST"
echo "  Time: $(date -u +"%Y-%m-%dT%H:%M:%SZ")"
echo "========================================="

PASS_COUNT=0
FAIL_COUNT=0

# Source all test functions or run inline
# ... (all tests above) ...

echo ""
echo "========================================="
echo "  RESULTS: $PASS_COUNT passed, $FAIL_COUNT failed"
echo "========================================="

if [ "$FAIL_COUNT" -gt 0 ]; then
  echo "SMOKE TESTS FAILED — DO NOT PROCEED TO NEXT CANARY PHASE"
  exit 1
else
  echo "SMOKE TESTS PASSED — Safe to proceed"
  exit 0
fi
```

---

## Expected Results Summary

| Test | Expected HTTP | Expected Response |
|---|---|---|
| Health check | 200 | `{"status":"UP"}` |
| JWKS endpoint | 200 | RSA key with `kid` |
| Admin login | 200 | `"success":true`, OTP required |
| User signup | 200 | `"success":true`, JWT returned |
| User login | 200 | `"success":true`, OTP required |
| OTP verify | 200 | `"success":true`, JWT returned |
| Profile (valid JWT) | 200 | User data |
| Profile (invalid JWT) | 401 | Unauthorized |
| Profile (no JWT) | 401 | Unauthorized |
| Create URL | 200 | `"success":true`, shortCode returned |
| Redirect URL | 302 → 200 | Redirects to original URL |
| Redirect non-existent | 404 | Not found |
| Delete URL | 200 | `"success":true` |
| Redirect after delete | 404 | Not found (cache invalidated) |
| javascript: URL | 400/200-false | Rejected |
| data: URL | 400/200-false | Rejected |
| Internal IP URL | 400/200-false | Rejected |
| Cloud metadata URL | 400/200-false | Rejected |
| Forgot password (exists) | 200 | `"success":true` |
| Forgot password (nonexistent) | 200 | `"success":true` (anti-enumeration) |
| Admin stats (admin) | 200 | User counts |
| Admin stats (user) | 403 | Forbidden |
| Admin stats (no auth) | 401/403 | Unauthorized |
| Global flags (public) | 200 | Flag list |
| User features (auth) | 200 | Feature list |
| Self-invite | 200 | Success or feature-disabled |
| Delete account | 200 | `"success":true` |
| Login after delete | 401/404 | Rejected |
