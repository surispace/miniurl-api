#!/usr/bin/env bash
# Tear down MiniURL from Minikube, optionally destroy the cluster
set -euo pipefail

NAMESPACE="${NAMESPACE:-miniurl-local}"
RELEASE_NAME="${RELEASE_NAME:-miniurl}"
MINIKUBE_PROFILE="${MINIKUBE_PROFILE:-miniurl}"
DESTROY_CLUSTER="${DESTROY_CLUSTER:-false}"

echo "=== MiniURL Minikube Teardown ==="

# Uninstall Helm release
if helm status "${RELEASE_NAME}" -n "${NAMESPACE}" &>/dev/null; then
  echo "Uninstalling Helm release '${RELEASE_NAME}'..."
  helm uninstall "${RELEASE_NAME}" -n "${NAMESPACE}" --wait
  echo "  Release removed"
else
  echo "No Helm release '${RELEASE_NAME}' found in namespace '${NAMESPACE}'"
fi

# Delete namespace
if kubectl get namespace "${NAMESPACE}" &>/dev/null; then
  echo "Deleting namespace '${NAMESPACE}'..."
  kubectl delete namespace "${NAMESPACE}" --wait --timeout=2m
  echo "  Namespace removed"
fi

# Optionally destroy cluster
if [ "${DESTROY_CLUSTER}" = "true" ]; then
  if minikube status -p "${MINIKUBE_PROFILE}" &>/dev/null; then
    echo "Destroying Minikube cluster '${MINIKUBE_PROFILE}'..."
    minikube delete -p "${MINIKUBE_PROFILE}"
    echo "  Cluster destroyed"
  else
    echo "Minikube cluster '${MINIKUBE_PROFILE}' not running"
  fi
else
  echo "Cluster '${MINIKUBE_PROFILE}' left running (set DESTROY_CLUSTER=true to remove)"
fi

echo ""
echo "=== Teardown complete ==="
