#!/usr/bin/env bash
# Smoke tests for post-deployment validation
# Usage: ./scripts/deploy/smoke-test.sh <env>
#   env: dev, staging, prod (defaults to dev)
set -euo pipefail

ENV="${1:-dev}"
BASE_URL="${BASE_URL:-http://localhost:8080}"

# Map env to expected host header and namespace
case "$ENV" in
  dev)     HOST="${SMOKE_HOST:-dev.api.miniurl.com}";     NS="miniurl" ;;
  staging) HOST="${SMOKE_HOST:-staging.api.miniurl.com}"; NS="miniurl" ;;
  prod)    HOST="${SMOKE_HOST:-api.miniurl.com}";         NS="miniurl" ;;
  *)       HOST="${SMOKE_HOST}";                          NS="miniurl" ;;
esac

echo "=== MiniURL Smoke Tests — ${ENV} ==="
echo "Host: ${HOST:-<none>}"
FAILURES=0

# ── 1. API Gateway Health ──────────────────────────────────
echo "--- 1. Health Checks ---"
for endpoint in \
  "api-gateway:/actuator/health:200,401" \
  "identity-service:/actuator/health:200,401" \
  "redirect-service:/actuator/health:200,401" \
  "url-service:/actuator/health:200,401"; do
  name="${endpoint%%:*}"
  rest="${endpoint#*:}"
  path="${rest%%:*}"
  expected_codes="${rest##*:}"
  # Use kubectl port-forward or ingress if available
  if command -v kubectl &>/dev/null && kubectl -n "$NS" get pod -l "app=${name}" &>/dev/null 2>&1; then
    status=$(kubectl -n "$NS" exec "deployment/${name}" -- curl -s -o /dev/null -w "%{http_code}" "http://localhost${path}" 2>/dev/null || echo "000")
  else
    status=$(curl -s -o /dev/null -w "%{http_code}" "${BASE_URL}${path}" 2>/dev/null || echo "000")
  fi
  echo "  ${name}: ${status}"
done

# ── 2. JWKS Endpoint ───────────────────────────────────────
echo "--- 2. JWKS Endpoint ---"
JWKS_URL="${BASE_URL}/.well-known/jwks.json"
if command -v kubectl &>/dev/null; then
  jwks_status=$(kubectl -n "$NS" exec deployment/api-gateway -- curl -s -o /dev/null -w "%{http_code}" "http://localhost:8080/.well-known/jwks.json" 2>/dev/null || echo "000")
else
  jwks_status=$(curl -s -o /dev/null -w "%{http_code}" "$JWKS_URL" 2>/dev/null || echo "000")
fi
echo "  JWKS: ${jwks_status}"
if [ "$jwks_status" != "200" ]; then
  echo "  WARN: JWKS endpoint returned ${jwks_status}"
  FAILURES=$((FAILURES + 1))
fi

# ── 3. Auth Signup (Public Endpoint) ───────────────────────
echo "--- 3. Auth Signup ---"
if [ -n "${HOST:-}" ]; then
  signup_status=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "${BASE_URL}/api/auth/signup" \
    -H "Content-Type: application/json" \
    -H "Host: ${HOST}" \
    -d '{"username":"smoketest","email":"smoke@test.com","password":"Test1234!"}' 2>/dev/null || echo "000")
else
  signup_status=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "${BASE_URL}/api/auth/signup" \
    -H "Content-Type: application/json" \
    -d '{"username":"smoketest","email":"smoke@test.com","password":"Test1234!"}' 2>/dev/null || echo "000")
fi
echo "  Signup: ${signup_status}"

# ── 4. Redirect (expect 404 for nonexistent code) ──────────
echo "--- 4. Redirect ---"
redirect_status=$(curl -s -o /dev/null -w "%{http_code}" "${BASE_URL}/r/nonexistent" 2>/dev/null || echo "000")
echo "  Redirect (/r/nonexistent): ${redirect_status}"
if [ "$redirect_status" != "302" ] && [ "$redirect_status" != "404" ]; then
  echo "  WARN: Unexpected redirect status: ${redirect_status}"
fi

# ── 5. Feature Flags ───────────────────────────────────────
echo "--- 5. Feature Flags ---"
ff_status=$(curl -s -o /dev/null -w "%{http_code}" "${BASE_URL}/api/features" 2>/dev/null || echo "000")
echo "  Feature flags: ${ff_status}"

# ── Summary ────────────────────────────────────────────────
echo "=== Smoke Tests Complete ==="
if [ "$FAILURES" -gt 0 ]; then
  echo "FAILURES: ${FAILURES}"
  exit 1
fi
echo "All critical checks passed."
