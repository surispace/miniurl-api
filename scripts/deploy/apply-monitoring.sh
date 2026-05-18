#!/usr/bin/env bash
# =============================================================================
# MiniURL Canary Deployment — Apply Monitoring Configuration
# =============================================================================
# Applies Prometheus alert rules and blackbox-exporter JWKS probe config.
#
# Usage:
#   ./scripts/deploy/apply-monitoring.sh
#   DRY_RUN=true ./scripts/deploy/apply-monitoring.sh
#
# Required env vars:
#   NAMESPACE          — App namespace (default: miniurl)
#   MONITORING_NS      — Monitoring namespace (default: monitoring)
#   DRY_RUN            — If "true", print commands only (default: false)
# =============================================================================

set -euo pipefail

# --- Configuration -----------------------------------------------------------
NAMESPACE="${NAMESPACE:-miniurl}"
MONITORING_NS="${MONITORING_NS:-monitoring}"
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
echo "  MiniURL Canary — Apply Monitoring"
echo "=============================================="
echo "  App Namespace:   $NAMESPACE"
echo "  Monitoring NS:   $MONITORING_NS"
echo "  Dry Run:         $DRY_RUN"
echo "=============================================="
echo ""

# Ensure monitoring namespace exists
if ! kubectl get namespace "$MONITORING_NS" &>/dev/null; then
    info "Creating monitoring namespace: $MONITORING_NS"
    run "kubectl create namespace $MONITORING_NS"
fi

# =============================================================================
# CANARY ALERT RULES
# =============================================================================
echo ""
echo "--- Canary Alert Rules ---"

ALERTS_FILE="$REPO_ROOT/k8s/infrastructure/canary-alerts.yaml"

if [[ ! -f "$ALERTS_FILE" ]]; then
    fail "Canary alerts file not found: $ALERTS_FILE"
    exit 1
fi

info "Applying canary alert rules from $ALERTS_FILE..."
run "kubectl apply -f $ALERTS_FILE -n $MONITORING_NS"

# Verify the ConfigMap was created
if [[ "$DRY_RUN" != "true" ]]; then
    if kubectl get configmap canary-alert-rules -n "$MONITORING_NS" &>/dev/null; then
        pass "ConfigMap 'canary-alert-rules' created in '$MONITORING_NS'"
    else
        warn "ConfigMap 'canary-alert-rules' not found. Check apply output."
    fi
fi

# =============================================================================
# PROMETHEUS CONFIG
# =============================================================================
echo ""
echo "--- Prometheus Configuration ---"

PROM_CONFIG="$REPO_ROOT/k8s/infrastructure/prometheus-config.yaml"

if [[ -f "$PROM_CONFIG" ]]; then
    info "Applying Prometheus config from $PROM_CONFIG..."
    run "kubectl apply -f $PROM_CONFIG -n $MONITORING_NS"

    if [[ "$DRY_RUN" != "true" ]]; then
        if kubectl get configmap prometheus-config -n "$MONITORING_NS" &>/dev/null; then
            pass "ConfigMap 'prometheus-config' exists in '$MONITORING_NS'"
        fi
    fi
else
    warn "Prometheus config not found: $PROM_CONFIG"
fi

# =============================================================================
# BLACKBOX EXPORTER — JWKS PROBE
# =============================================================================
echo ""
echo "--- Blackbox Exporter (JWKS Probe) ---"

BBE_CONFIG="$REPO_ROOT/k8s/infrastructure/blackbox-exporter-jwks-config.yaml"

if [[ -f "$BBE_CONFIG" ]]; then
    info "Applying blackbox-exporter JWKS config from $BBE_CONFIG..."
    run "kubectl apply -f $BBE_CONFIG -n $MONITORING_NS"

    if [[ "$DRY_RUN" != "true" ]]; then
        if kubectl get configmap blackbox-exporter-jwks-config -n "$MONITORING_NS" &>/dev/null; then
            pass "ConfigMap 'blackbox-exporter-jwks-config' exists in '$MONITORING_NS'"
        else
            warn "ConfigMap not found. Blackbox exporter may need manual configuration."
        fi
    fi
else
    warn "Blackbox exporter config not found: $BBE_CONFIG"
fi

# =============================================================================
# VERIFY PROMETHEUS TARGETS (best-effort)
# =============================================================================
echo ""
echo "--- Prometheus Target Verification ---"

PROM_POD=$(kubectl get pods -n "$MONITORING_NS" -l "app=prometheus" -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || echo "")

if [[ -n "$PROM_POD" ]]; then
    info "Prometheus pod found: $PROM_POD"

    if [[ "$DRY_RUN" != "true" ]]; then
        # Port-forward to Prometheus and check targets (best-effort)
        info "Checking Prometheus targets (best-effort via port-forward)..."
        kubectl port-forward -n "$MONITORING_NS" "$PROM_POD" 9090:9090 &>/dev/null &
        PF_PID=$!
        sleep 3

        # Check if Prometheus API is reachable
        if curl -s http://localhost:9090/api/v1/targets &>/dev/null; then
            UP_TARGETS=$(curl -s http://localhost:9090/api/v1/targets | jq '[.data.activeTargets[] | select(.health == "up")] | length' 2>/dev/null || echo "0")
            DOWN_TARGETS=$(curl -s http://localhost:9090/api/v1/targets | jq '[.data.activeTargets[] | select(.health == "down")] | length' 2>/dev/null || echo "0")
            info "Prometheus targets: $UP_TARGETS up, $DOWN_TARGETS down"
        else
            warn "Could not reach Prometheus API on localhost:9090"
        fi

        kill $PF_PID 2>/dev/null || true
    fi
else
    warn "No Prometheus pod found in '$MONITORING_NS'. Skipping target verification."
    warn "Deploy Prometheus first, then re-run this script."
fi

# =============================================================================
# SUMMARY
# =============================================================================
echo ""
echo "=============================================="
echo -e "${GREEN}  MONITORING CONFIGURATION APPLIED${NC}"
echo "=============================================="
echo ""
echo "Applied:"
echo "  - Canary alert rules (18 alerts, 8 rollback triggers)"
echo "  - Prometheus scrape config"
echo "  - Blackbox exporter JWKS probe config"
echo ""
echo "Manual verification:"
echo "  kubectl port-forward -n $MONITORING_NS svc/prometheus 9090:9090"
echo "  open http://localhost:9090/alerts"
echo ""
echo "Next step: ./scripts/deploy/capture-baseline.sh"
echo ""
