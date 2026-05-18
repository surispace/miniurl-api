#!/usr/bin/env bash
# Build all MiniURL Docker images inside Minikube's Docker daemon
# Idempotent — rebuilds changed layers only
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
MINIKUBE_PROFILE="${MINIKUBE_PROFILE:-miniurl}"
IMAGE_PREFIX="${IMAGE_PREFIX:-miniurl}"
IMAGE_TAG="${IMAGE_TAG:-local}"

SERVICES=(
  eureka-server
  api-gateway
  identity-service
  url-service
  redirect-service
  feature-service
  notification-service
  analytics-service
)

echo "=== Building MiniURL images inside Minikube ==="
echo "Profile:   ${MINIKUBE_PROFILE}"
echo "Prefix:    ${IMAGE_PREFIX}"
echo "Tag:       ${IMAGE_TAG}"
echo ""

# Point Docker CLI at Minikube's Docker daemon
eval "$(minikube docker-env -p "${MINIKUBE_PROFILE}")"

echo "Using Minikube Docker daemon: ${DOCKER_HOST:-unset}"

cd "${REPO_ROOT}"

for svc in "${SERVICES[@]}"; do
  echo ""
  echo "--- Building ${svc}:${IMAGE_TAG} ---"
  docker build \
    --target "${svc}" \
    -t "${IMAGE_PREFIX}/${svc}:${IMAGE_TAG}" \
    .
  echo "  ${IMAGE_PREFIX}/${svc}:${IMAGE_TAG} — done"
done

echo ""
echo "=== All images built ==="
docker images "${IMAGE_PREFIX}/*:${IMAGE_TAG}"
echo ""
echo "Next: ./scripts/local/minikube-deploy.sh"
