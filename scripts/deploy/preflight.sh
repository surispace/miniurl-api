#!/usr/bin/env bash
# =============================================================================
# MiniURL Canary Deployment — Preflight Check
# =============================================================================
# Validates all prerequisites before starting canary deployment.
#
# Usage:
#   ./scripts/deploy/preflight.sh
#   NAMESPACE=miniurl ./scripts/deploy/preflight.sh
#   DRY_RUN=true ./scripts/deploy/preflight.sh
#
# Required env vars (or defaults):
#   NAMESPACE          — Kubernetes namespace (default: miniurl)
#   MONITORING_NS      — Monitoring namespace (default: monitoring)
#   DRY_RUN            — If "true", skip destructive checks (default: false)
# =============================================================================

set -euo pipefail

# --- Configuration -----------------------------------------------------------
NAMESPACE="${NAMESPACE:-miniurl}"
MONITORING_NS="${MONITORING_NS:-monitoring}"
DRY_RUN="${DRY_RUN:-false}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# --- Color output ------------------------------------------------------------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

pass() { echo -e "${GREEN}[PASS]${NC} $*"; }
fail() { echo -e "${RED}[FAIL]${NC} $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
info() { echo -e "[INFO] $*"; }

# --- Banner ------------------------------------------------------------------
echo ""
echo "=============================================="
echo "  MiniURL Canary — Preflight Check"
echo "=============================================="
echo "  Namespace:       $NAMESPACE"
echo "  Monitoring NS:   $MONITORING_NS"
echo "  Dry Run:         $DRY_RUN"
echo "=============================================="
echo ""

FAILURES=0

# --- Tool Validation ---------------------------------------------------------
info "Checking required tools..."

check_tool() {
    local tool="$1"
    local hint="${2:-}"
    if command -v "$tool" &>/dev/null; then
        pass "$tool found: $(command -v "$tool")"
    else
        fail "$tool NOT found. ${hint}"
        FAILURES=$((FAILURES + 1))
    fi
}

check_tool "kubectl" "Install: brew install kubectl"
check_tool "helm"    "Install: brew install helm"
check_tool "openssl" "Install: brew install openssl"
check_tool "curl"    "Install: brew install curl"
check_tool "jq"      "Install: brew install jq"

# mysql client is optional — we can use kubectl exec as fallback
if command -v mysql &>/dev/null; then
    pass "mysql found: $(command -v mysql)"
else
    warn "mysql client not found. Will use 'kubectl exec' for DB operations."
fi

echo ""

# --- Kubernetes Connectivity -------------------------------------------------
info "Checking Kubernetes connectivity..."

if kubectl cluster-info &>/dev/null; then
    pass "kubectl can reach cluster"
    CTX=$(kubectl config current-context 2>/dev/null || echo "unknown")
    info "  Current context: $CTX"
else
    fail "kubectl cannot reach cluster. Check KUBECONFIG or current context."
    FAILURES=$((FAILURES + 1))
fi

echo ""

# --- Namespace Validation ----------------------------------------------------
info "Checking namespaces..."

ns_exists() {
    kubectl get namespace "$1" &>/dev/null
}

if ns_exists "$NAMESPACE"; then
    pass "Namespace '$NAMESPACE' exists"
else
    if [[ "$DRY_RUN" == "true" ]]; then
        warn "Namespace '$NAMESPACE' does not exist (would be created)"
    else
        fail "Namespace '$NAMESPACE' does not exist. Create it first:"
        fail "  kubectl create namespace $NAMESPACE"
        FAILURES=$((FAILURES + 1))
    fi
fi

if ns_exists "$MONITORING_NS"; then
    pass "Monitoring namespace '$MONITORING_NS' exists"
else
    if [[ "$DRY_RUN" == "true" ]]; then
        warn "Monitoring namespace '$MONITORING_NS' does not exist (would be created)"
    else
        fail "Monitoring namespace '$MONITORING_NS' does not exist. Create it first:"
        fail "  kubectl create namespace $MONITORING_NS"
        FAILURES=$((FAILURES + 1))
    fi
fi

echo ""

# --- Helm Repos --------------------------------------------------------------
info "Checking Helm repositories..."

helm_repo_exists() {
    helm repo list 2>/dev/null | grep -q "^$1"
}

for repo in bitnami; do
    if helm_repo_exists "$repo"; then
        pass "Helm repo '$repo' found"
    else
        warn "Helm repo '$repo' not found. Add it with:"
        warn "  helm repo add $repo https://charts.bitnami.com/bitnami"
        warn "  helm repo update"
    fi
done

echo ""

# --- Config Files ------------------------------------------------------------
info "Checking required config files..."

check_file() {
    local file="$1"
    local desc="$2"
    if [[ -f "$REPO_ROOT/$file" ]]; then
        pass "$desc: $file"
    else
        fail "$desc NOT found: $file"
        FAILURES=$((FAILURES + 1))
    fi
}

check_file "k8s/infrastructure/canary-alerts.yaml"           "Canary alert rules"
check_file "k8s/infrastructure/blackbox-exporter-jwks-config.yaml" "JWKS probe config"
check_file "k8s/infrastructure/global-config.yaml"           "Global ConfigMap"
check_file "k8s/infrastructure/prometheus-config.yaml"       "Prometheus config"
check_file "scripts/init-identity-db.sql"                    "Identity DB init SQL"
check_file "scripts/init-url-db.sql"                         "URL DB init SQL"
check_file "scripts/init-feature-db.sql"                     "Feature DB init SQL"
check_file "scripts/init-db.sql"                             "Main DB init SQL"

echo ""

# --- StorageClass Check ------------------------------------------------------
info "Checking storage classes..."

if kubectl get storageclass &>/dev/null; then
    DEFAULT_SC=$(kubectl get storageclass -o json | jq -r '.items[] | select(.metadata.annotations."storageclass.kubernetes.io/is-default-class" == "true") | .metadata.name' 2>/dev/null || echo "")
    if [[ -n "$DEFAULT_SC" ]]; then
        pass "Default StorageClass: $DEFAULT_SC"
    else
        warn "No default StorageClass found. PVCs may not bind."
    fi
else
    warn "Cannot list StorageClasses (may need cluster-admin permissions)"
fi

echo ""

# --- Resource Availability ---------------------------------------------------
info "Checking cluster resource availability..."

if [[ "$DRY_RUN" != "true" ]]; then
    NODE_COUNT=$(kubectl get nodes --no-headers 2>/dev/null | wc -l || echo "0")
    if [[ "$NODE_COUNT" -gt 0 ]]; then
        pass "Cluster has $NODE_COUNT node(s)"
    else
        warn "Cannot determine node count"
    fi
fi

echo ""

# --- Summary -----------------------------------------------------------------
echo "=============================================="
if [[ "$FAILURES" -eq 0 ]]; then
    echo -e "${GREEN}  PREFLIGHT: ALL CHECKS PASSED${NC}"
    echo "=============================================="
    echo ""
    echo "Ready to proceed with:"
    echo "  1. ./scripts/deploy/bootstrap-infra.sh"
    echo "  2. ./scripts/deploy/create-secrets.sh"
    echo "  3. ./scripts/deploy/run-migrations.sh"
    echo "  4. ./scripts/deploy/apply-monitoring.sh"
    echo "  5. ./scripts/deploy/capture-baseline.sh"
    echo "  6. ./scripts/deploy/start-canary.sh"
    echo ""
    exit 0
else
    echo -e "${RED}  PREFLIGHT: $FAILURES CHECK(S) FAILED${NC}"
    echo "=============================================="
    echo ""
    echo "Fix the failures above before proceeding."
    echo ""
    exit 1
fi
