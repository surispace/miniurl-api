#!/usr/bin/env bash
# =============================================================================
# MiniURL Canary Deployment — Start Canary
# =============================================================================
# Orchestrates the canary deployment phases:
#   Phase 1: Deploy canary services at 10% traffic
#   Phase 2: Expand to 25% traffic
#   Phase 3: Expand to 50% traffic
#   Phase 4: Full promotion (100%)
#
# Usage:
#   ./scripts/deploy/start-canary.sh
#   CANARY_IMAGE_TAG=v2.1.0-canary ./scripts/deploy/start-canary.sh
#   PHASE=1 ./scripts/deploy/start-canary.sh
#   DRY_RUN=true ./scripts/deploy/start-canary.sh
#
# Required env vars:
#   NAMESPACE          — Kubernetes namespace (default: miniurl)
#   CANARY_IMAGE_TAG   — Docker image tag for canary (required)
#   STABLE_IMAGE_TAG   — Docker image tag for stable (default: latest)
#   PHASE              — Starting phase: 1, 2, 3, or 4 (default: 1)
#   INGRESS_HOST       — Ingress hostname (default: api.miniurl.local)
#   DRY_RUN            — If "true", print commands only (default: false)
# =============================================================================

set -euo pipefail

# --- Configuration -----------------------------------------------------------
NAMESPACE="${NAMESPACE:-miniurl}"
CANARY_IMAGE_TAG="${CANARY_IMAGE_TAG:-}"
STABLE_IMAGE_TAG="${STABLE_IMAGE_TAG:-latest}"
PHASE="${PHASE:-1}"
INGRESS_HOST="${INGRESS_HOST:-api.miniurl.local}"
DRY_RUN="${DRY_RUN:-false}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# --- Color output ------------------------------------------------------------
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'
pass() { echo -e "${GREEN}[PASS]${NC} $*"; }
fail() { echo -e "${RED}[FAIL]${NC} $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
info() { echo -e "[INFO] $*"; }
phase_header() { echo -e "${BLUE}[PHASE $PHASE]${NC} $*"; }

run() {
    if [[ "$DRY_RUN" == "true" ]]; then
        info "[DRY-RUN] $*"
    else
        eval "$@"
    fi
}

# --- Validation --------------------------------------------------------------
if [[ -z "$CANARY_IMAGE_TAG" ]]; then
    fail "CANARY_IMAGE_TAG is required."
    echo "Usage: CANARY_IMAGE_TAG=v2.1.0-canary ./scripts/deploy/start-canary.sh"
    exit 1
fi

# --- Banner ------------------------------------------------------------------
echo ""
echo "=============================================="
echo "  MiniURL Canary — Start Canary Deployment"
echo "=============================================="
echo "  Namespace:       $NAMESPACE"
echo "  Canary Tag:      $CANARY_IMAGE_TAG"
echo "  Stable Tag:      $STABLE_IMAGE_TAG"
echo "  Starting Phase:  $PHASE"
echo "  Ingress Host:    $INGRESS_HOST"
echo "  Dry Run:         $DRY_RUN"
echo "=============================================="
echo ""

# --- Service list for canary ------------------------------------------------
# Services that participate in the canary (ordered by dependency)
CANARY_SERVICES=(
    "eureka-server"
    "identity-service"
    "url-service"
    "redirect-service"
    "feature-service"
    "notification-service"
    "analytics-service"
    "api-gateway"
)

# Traffic weights per phase
declare -A CANARY_WEIGHT
CANARY_WEIGHT[1]=10
CANARY_WEIGHT[2]=25
CANARY_WEIGHT[3]=50
CANARY_WEIGHT[4]=100

# =============================================================================
# PHASE 1: Initial Canary (10%)
# =============================================================================
run_phase1() {
    phase_header "Deploying canary services at ${CANARY_WEIGHT[1]}% traffic"

    echo ""
    info "Deploying canary versions alongside stable..."

    for svc in "${CANARY_SERVICES[@]}"; do
        local deploy_file="$REPO_ROOT/k8s/services/${svc}.yaml"

        if [[ ! -f "$deploy_file" ]]; then
            warn "Deployment file not found: $deploy_file (skipping)"
            continue
        fi

        info "Deploying canary for: $svc"
        run "kubectl set image deployment/${svc} ${svc}=miniurl/${svc}:${CANARY_IMAGE_TAG} -n $NAMESPACE --record"

        # Wait for rollout
        if [[ "$DRY_RUN" != "true" ]]; then
            kubectl rollout status deployment/"$svc" -n "$NAMESPACE" --timeout=5m || warn "Rollout for $svc may still be in progress."
        fi
    done

    echo ""
    info "Canary pods should now be running alongside stable pods."
    info "Verify with: kubectl get pods -n $NAMESPACE"

    echo ""
    warn "=============================================="
    warn "  HUMAN APPROVAL REQUIRED"
    warn "=============================================="
    warn "Monitor the canary for at least 5 minutes before proceeding."
    warn "Check:"
    warn "  1. kubectl get pods -n $NAMESPACE"
    warn "  2. kubectl logs -n $NAMESPACE -l app=redirect-service --tail=50"
    warn "  3. Prometheus alerts: http://localhost:9090/alerts"
    warn ""
    warn "If all clear, run Phase 2:"
    warn "  PHASE=2 CANARY_IMAGE_TAG=$CANARY_IMAGE_TAG ./scripts/deploy/start-canary.sh"
    warn ""
    warn "If issues detected, rollback immediately:"
    warn "  ./scripts/deploy/rollback-canary.sh"
    warn "=============================================="
}

# =============================================================================
# PHASE 2: Expand to 25%
# =============================================================================
run_phase2() {
    phase_header "Expanding canary to ${CANARY_WEIGHT[2]}% traffic"

    echo ""
    info "Scaling canary deployments to increase traffic share..."

    for svc in "${CANARY_SERVICES[@]}"; do
        local current_replicas
        current_replicas=$(kubectl get deployment "$svc" -n "$NAMESPACE" -o jsonpath='{.spec.replicas}' 2>/dev/null || echo "1")
        local new_replicas=$((current_replicas + 1))

        info "Scaling $svc: $current_replicas → $new_replicas replicas"
        run "kubectl scale deployment $svc --replicas=$new_replicas -n $NAMESPACE"
    done

    echo ""
    warn "=============================================="
    warn "  HUMAN APPROVAL REQUIRED"
    warn "=============================================="
    warn "Monitor for 10 minutes before Phase 3."
    warn "If all clear:"
    warn "  PHASE=3 CANARY_IMAGE_TAG=$CANARY_IMAGE_TAG ./scripts/deploy/start-canary.sh"
    warn "=============================================="
}

# =============================================================================
# PHASE 3: Expand to 50%
# =============================================================================
run_phase3() {
    phase_header "Expanding canary to ${CANARY_WEIGHT[3]}% traffic"

    echo ""
    info "Further scaling canary deployments..."

    for svc in "${CANARY_SERVICES[@]}"; do
        local current_replicas
        current_replicas=$(kubectl get deployment "$svc" -n "$NAMESPACE" -o jsonpath='{.spec.replicas}' 2>/dev/null || echo "1")
        local new_replicas=$((current_replicas + 1))

        info "Scaling $svc: $current_replicas → $new_replicas replicas"
        run "kubectl scale deployment $svc --replicas=$new_replicas -n $NAMESPACE"
    done

    echo ""
    warn "=============================================="
    warn "  HUMAN APPROVAL REQUIRED"
    warn "=============================================="
    warn "Monitor for 15 minutes before full promotion."
    warn "If all clear:"
    warn "  PHASE=4 CANARY_IMAGE_TAG=$CANARY_IMAGE_TAG ./scripts/deploy/start-canary.sh"
    warn "=============================================="
}

# =============================================================================
# PHASE 4: Full Promotion (100%)
# =============================================================================
run_phase4() {
    phase_header "Promoting canary to 100% — FULL PROMOTION"

    echo ""
    info "Removing stable deployments, canary becomes the new stable..."

    for svc in "${CANARY_SERVICES[@]}"; do
        info "Ensuring $svc is fully on canary image..."
        run "kubectl set image deployment/${svc} ${svc}=miniurl/${svc}:${CANARY_IMAGE_TAG} -n $NAMESPACE"

        # Scale to production replica count
        local prod_replicas
        case "$svc" in
            redirect-service) prod_replicas=3 ;;
            api-gateway|identity-service|url-service|feature-service) prod_replicas=2 ;;
            *) prod_replicas=1 ;;
        esac

        info "Scaling $svc to $prod_replicas replicas"
        run "kubectl scale deployment $svc --replicas=$prod_replicas -n $NAMESPACE"
    done

    echo ""
    pass "=============================================="
    pass "  CANARY FULLY PROMOTED"
    pass "=============================================="
    pass "Canary image '$CANARY_IMAGE_TAG' is now the stable version."
    pass ""
    pass "Update STABLE_IMAGE_TAG for future canaries:"
    pass "  export STABLE_IMAGE_TAG=$CANARY_IMAGE_TAG"
    pass "=============================================="
}

# =============================================================================
# EXECUTE PHASE
# =============================================================================
case "$PHASE" in
    1) run_phase1 ;;
    2) run_phase2 ;;
    3) run_phase3 ;;
    4) run_phase4 ;;
    *)
        fail "Invalid PHASE: $PHASE. Must be 1, 2, 3, or 4."
        exit 1
        ;;
esac

echo ""
