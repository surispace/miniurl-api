#!/usr/bin/env bash
# Smoke test MiniURL running in Minikube
set -euo pipefail

NAMESPACE="${NAMESPACE:-miniurl-local}"
RELEASE_NAME="${RELEASE_NAME:-miniurl}"
FAILURES=0

echo "=== MiniURL Minikube Smoke Tests ==="
echo "Namespace: ${NAMESPACE}"
echo ""

# 1. All pods Running
echo "--- 1. Pod status ---"
READY=$(kubectl -n "${NAMESPACE}" get pods -o jsonpath='{range .items[*]}{.status.phase}{"\n"}{end}' | grep -c "Running" || echo "0")
TOTAL=$(kubectl -n "${NAMESPACE}" get pods --no-headers 2>/dev/null | wc -l | tr -d ' ')
echo "  ${READY}/${TOTAL} pods Running"
if [ "${READY}" -lt "${TOTAL}" ]; then
  echo "  WARN: Not all pods are Running"
  kubectl -n "${NAMESPACE}" get pods
fi

# 2. API Gateway health via port-forward
echo ""
echo "--- 2. API Gateway health ---"
PF_PID=""
cleanup() { [ -n "${PF_PID:-}" ] && kill "${PF_PID}" 2>/dev/null || true; }
trap cleanup EXIT

kubectl -n "${NAMESPACE}" port-forward "svc/api-gateway" 18080:80 &>/dev/null &
PF_PID=$!
sleep 3

HEALTH=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:18080/actuator/health" 2>/dev/null || echo "000")
echo "  Health: ${HEALTH}"
if [ "$HEALTH" != "200" ]; then
  echo "  WARN: Health check returned ${HEALTH}"
  FAILURES=$((FAILURES + 1))
fi

# 3. JWKS
echo ""
echo "--- 3. JWKS endpoint ---"
JWKS=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:18080/.well-known/jwks.json" 2>/dev/null || echo "000")
echo "  JWKS: ${JWKS}"
if [ "$JWKS" != "200" ]; then
  echo "  WARN: JWKS returned ${JWKS}"
  FAILURES=$((FAILURES + 1))
fi

# 4. Redirect service
echo ""
echo "--- 4. Redirect ---"
REDIR=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:18080/r/nonexistent" 2>/dev/null || echo "000")
echo "  /r/nonexistent: ${REDIR} (302 or 404 expected)"

# 5. Auth signup
echo ""
echo "--- 5. Auth signup ---"
SIGNUP=$(curl -s -o /dev/null -w "%{http_code}" \
  -X POST "http://localhost:18080/api/auth/signup" \
  -H "Content-Type: application/json" \
  -d '{"username":"smoketest","email":"smoke@test.local","password":"Test1234!"}' 2>/dev/null || echo "000")
echo "  Signup: ${SIGNUP}"

# Cleanup port-forward
cleanup
trap - EXIT

echo ""
echo "=== Smoke Tests Complete ==="
if [ "$FAILURES" -gt 0 ]; then
  echo "FAILURES: ${FAILURES}"
  exit 1
fi
echo "All checks passed."
