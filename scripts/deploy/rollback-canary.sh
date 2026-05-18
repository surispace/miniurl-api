#!/usr/bin/env bash
# =============================================================================
# MiniURL Canary Deployment — Rollback Canary
# =============================================================================
# Rolls back all services from canary image to stable image.
# This is the EMERGENCY ROLLBACK script — use when canary alerts fire.
#
# Usage:
#   ./scripts/deploy/rollback-canary.sh
#   STABLE_IMAGE_TAG=v2.0.0-stable ./scripts/deploy/rollback-canary.sh
#   DRY_RUN=true ./scripts/deploy/rollback-canary.sh
#
# Required env vars:
#   NAMESPACE          — Kubernetes namespace (default: miniurl)
#   STABLE_IMAGE_TAG   — Docker image tag to roll back to (default: latest)
#   DRY_RUN            — If "true", print commands only (default: false)
# =============================================================================

set -euo pipefail

# --- Configuration -----------------------------------------------------------
NAMESPACE="${NAMESPACE:-miniurl}"
STABLE_IMAGE_TAG="${STABLE_IMAGE_TAG:-latest}"
DRY_RUN="${DRY_RUN:-false}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# --- Color output ------------------------------------------------------------
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
pass() { echo -e "${GREEN}[PASS]${NC} $*"; }
fail() { echo -e "${RED}[FAIL]${NC} $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
info() { echo -e "[INFO] $*"; }

run() {
    if [[ "$DRY_RUN" == "true" ]]; then
        info "[DRY-RUN] $*"
    else
        eval "$@"
    fi
}

# --- Banner ------------------------------------------------------------------
echo ""
echo "=============================================="
echo "  ⚠️  MiniURL Canary — EMERGENCY ROLLBACK  ⚠️"
echo "=============================================="
echo "  Namespace:       $NAMESPACE"
echo "  Stable Tag:      $STABLE_IMAGE_TAG"
echo "  Dry Run:         $DRY_RUN"
echo "=============================================="
echo ""

# Confirmation (skip in dry-run)
if [[ "$DRY_RUN" != "true" ]]; then
    echo -e "${RED}WARNING: This will roll back ALL services to $STABLE_IMAGE_TAG.${NC}"
    read -r -p "Are you sure you want to rollback? [y/N] " CONFIRM
    if [[ ! "$CONFIRM" =~ ^[Yy]$ ]]; then
        info "Rollback cancelled."
        exit 0
    fi
fi

# --- Service list (reverse dependency order for rollback) --------------------
ROLLBACK_SERVICES=(
    "api-gateway"
    "analytics-service"
    "notification-service"
    "feature-service"
    "redirect-service"
    "url-service"
    "identity-service"
    "eureka-server"
)

# Production replica counts
declare -A PROD_REPLICAS
PROD_REPLICAS[redirect-service]=3
PROD_REPLICAS[api-gateway]=2
PROD_REPLICAS[identity-service]=2
PROD_REPLICAS[url-service]=2
PROD_REPLICAS[feature-service]=2
PROD_REPLICAS[notification-service]=1
PROD_REPLICAS[analytics-service]=1
PROD_REPLICAS[eureka-server]=1

# =============================================================================
# ROLLBACK
# =============================================================================
echo ""
info "Starting rollback to stable image: $STABLE_IMAGE_TAG"
echo ""

for svc in "${ROLLBACK_SERVICES[@]}"; do
    echo "--- Rolling back: $svc ---"

    # Check if deployment exists
    if ! kubectl get deployment "$svc" -n "$NAMESPACE" &>/dev/null; then
        warn "Deployment '$svc' not found in namespace '$NAMESPACE'. Skipping."
        continue
    fi

    # Revert image
    info "Reverting $svc to image miniurl/${svc}:${STABLE_IMAGE_TAG}"
    run "kubectl set image deployment/${svc} ${svc}=miniurl/${svc}:${STABLE_IMAGE_TAG} -n $NAMESPACE"

    # Scale to production replicas
    local replicas="${PROD_REPLICAS[$svc]:-1}"
    info "Scaling $svc to $replicas replicas"
    run "kubectl scale deployment $svc --replicas=$replicas -n $NAMESPACE"

    # Wait for rollout
    if [[ "$DRY_RUN" != "true" ]]; then
        info "Waiting for $svc rollout..."
        kubectl rollout status deployment/"$svc" -n "$NAMESPACE" --timeout=5m || {
            warn "Rollout for $svc timed out. Checking pod status..."
            kubectl get pods -n "$NAMESPACE" -l "app=$svc"
        }
    fi

    pass "$svc rolled back."
    echo ""
done

# =============================================================================
# VERIFICATION
# =============================================================================
echo ""
echo "--- Post-Rollback Verification ---"

if [[ "$DRY_RUN" != "true" ]]; then
    info "Pod status:"
    kubectl get pods -n "$NAMESPACE" || warn "Cannot list pods."

    echo ""
    info "Checking for any pods still on canary image..."
    CANARY_PODS=$(kubectl get pods -n "$NAMESPACE" -o json | jq -r '.items[] | select(.spec.containers[].image | contains("canary")) | .metadata.name' 2>/dev/null || echo "")
    if [[ -z "$CANARY_PODS" ]]; then
        pass "No canary pods remaining."
    else
        warn "Canary pods still running: $CANARY_PODS"
        warn "These may need manual cleanup."
    fi
fi

# =============================================================================
# SUMMARY
# =============================================================================
echo ""
echo "=============================================="
echo -e "${GREEN}  ROLLBACK COMPLETE${NC}"
echo "=============================================="
echo ""
echo "All services reverted to: $STABLE_IMAGE_TAG"
echo ""
echo "Post-rollback actions:"
echo "  1. Verify service health:"
echo "     kubectl get pods -n $NAMESPACE"
echo "  2. Check Prometheus alerts have cleared:"
echo "     kubectl port-forward -n monitoring svc/prometheus 9090:9090"
echo "     open http://localhost:9090/alerts"
echo "  3. Investigate root cause of canary failure"
echo "  4. Update plans/canary-runbook.md with incident notes"
echo ""
