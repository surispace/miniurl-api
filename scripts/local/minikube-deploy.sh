#!/usr/bin/env bash
# Deploy MiniURL to Minikube via Helm
# Idempotent — safe to re-run, upgrades existing release
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
RELEASE_NAME="${RELEASE_NAME:-miniurl}"
NAMESPACE="${NAMESPACE:-miniurl-local}"

echo "=== Deploying MiniURL to Minikube ==="
echo "Release:   ${RELEASE_NAME}"
echo "Namespace: ${NAMESPACE}"
echo ""

cd "${REPO_ROOT}"

# Verify we're talking to Minikube
CTX=$(kubectl config current-context 2>/dev/null || echo "")
echo "Current context: ${CTX}"
if [[ ! "${CTX}" =~ minikube|miniurl ]]; then
  echo "WARN: Current context '${CTX}' does not look like Minikube."
  echo "      Switch with: kubectl config use-context miniurl"
  echo ""
fi

# Verify images exist in Minikube's Docker
eval "$(minikube docker-env -p "${MINIKUBE_PROFILE:-miniurl}" 2>/dev/null || true)"
echo ""
echo "--- Available images ---"
docker images "miniurl/*:local" --format "table {{.Repository}}:{{.Tag}}" 2>/dev/null || echo "WARN: No local images found for miniurl/*:local"

# Deploy
echo ""
echo "--- Helm deploy ---"
helm upgrade --install "${RELEASE_NAME}" ./helm/miniurl \
  --values ./helm/miniurl/values-local.yaml \
  --namespace "${NAMESPACE}" \
  --create-namespace \
  --timeout 10m \
  --wait

# Verify
echo ""
echo "--- Deployments ---"
kubectl -n "${NAMESPACE}" get deployments

echo ""
echo "--- Pods ---"
kubectl -n "${NAMESPACE}" get pods -o wide

echo ""
echo "--- Services ---"
kubectl -n "${NAMESPACE}" get svc

echo ""
echo "--- Ingress ---"
kubectl -n "${NAMESPACE}" get ingress

echo ""
echo "=== Deploy complete ==="
echo ""
echo "Next: ./scripts/local/minikube-smoke-test.sh"
