#!/usr/bin/env bash
# Start Minikube cluster for MiniURL local development
# Idempotent — safe to run if cluster already exists
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MINIKUBE_PROFILE="${MINIKUBE_PROFILE:-miniurl}"
MINIKUBE_CPUS="${MINIKUBE_CPUS:-4}"
MINIKUBE_MEMORY="${MINIKUBE_MEMORY:-8192}"
MINIKUBE_K8S_VERSION="${MINIKUBE_K8S_VERSION:-stable}"

echo "=== MiniURL Minikube Start ==="
echo "Profile:   ${MINIKUBE_PROFILE}"
echo "CPUs:      ${MINIKUBE_CPUS}"
echo "Memory:    ${MINIKUBE_MEMORY} MB"
echo "K8s:       ${MINIKUBE_K8S_VERSION}"
echo ""

# Check if minikube is installed
if ! command -v minikube &>/dev/null; then
  echo "FATAL: minikube is not installed."
  echo "Install: https://minikube.sigs.k8s.io/docs/start/"
  exit 1
fi

# Start cluster (idempotent — no-op if already running)
if minikube status -p "${MINIKUBE_PROFILE}" &>/dev/null; then
  echo "Minikube profile '${MINIKUBE_PROFILE}' is already running."
else
  echo "Starting Minikube profile '${MINIKUBE_PROFILE}'..."
  minikube start \
    -p "${MINIKUBE_PROFILE}" \
    --cpus="${MINIKUBE_CPUS}" \
    --memory="${MINIKUBE_MEMORY}" \
    --kubernetes-version="${MINIKUBE_K8S_VERSION}" \
    --driver=docker
fi

# Enable addons
echo ""
echo "--- Enabling addons ---"
minikube addons enable ingress -p "${MINIKUBE_PROFILE}" 2>/dev/null || echo "  ingress already enabled"
minikube addons enable metrics-server -p "${MINIKUBE_PROFILE}" 2>/dev/null || echo "  metrics-server already enabled"

# Verify
echo ""
echo "--- Cluster status ---"
minikube status -p "${MINIKUBE_PROFILE}"
echo ""
kubectl get nodes
echo ""
echo "=== Minikube cluster ready ==="
echo ""
echo "Next: ./scripts/local/minikube-build-images.sh"
