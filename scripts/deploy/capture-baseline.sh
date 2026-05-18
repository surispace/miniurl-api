#!/usr/bin/env bash
# =============================================================================
# MiniURL Canary Deployment — Capture Baseline Metrics
# =============================================================================
# Queries Prometheus for current metrics and saves them as a baseline report.
#
# Usage:
#   ./scripts/deploy/capture-baseline.sh
#   PROMETHEUS_URL=http://localhost:9090 ./scripts/deploy/capture-baseline.sh
#   DRY_RUN=true ./scripts/deploy/capture-baseline.sh
#
# Required env vars:
#   NAMESPACE          — App namespace (default: miniurl)
#   MONITORING_NS      — Monitoring namespace (default: monitoring)
#   PROMETHEUS_URL     — Prometheus URL (default: http://localhost:9090)
#   DRY_RUN            — If "true", print commands only (default: false)
# =============================================================================

set -euo pipefail

# --- Configuration -----------------------------------------------------------
NAMESPACE="${NAMESPACE:-miniurl}"
MONITORING_NS="${MONITORING_NS:-monitoring}"
PROMETHEUS_URL="${PROMETHEUS_URL:-http://localhost:9090}"
DRY_RUN="${DRY_RUN:-false}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
TIMESTAMP_FILE=$(date -u +"%Y%m%d-%H%M%S")
OUTPUT_FILE="$REPO_ROOT/plans/baseline-metrics-${TIMESTAMP_FILE}.md"

# --- Color output ------------------------------------------------------------
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
pass() { echo -e "${GREEN}[PASS]${NC} $*"; }
fail() { echo -e "${RED}[FAIL]${NC} $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
info() { echo -e "[INFO] $*"; }

# --- Prometheus query helper -------------------------------------------------
prom_query() {
    local query="$1"
    local desc="$2"

    if [[ "$DRY_RUN" == "true" ]]; then
        echo "  **$desc:** [DRY-RUN — would query Prometheus]"
        return
    fi

    local result
    result=$(curl -s --max-time 10 "${PROMETHEUS_URL}/api/v1/query" \
        --data-urlencode "query=$query" 2>/dev/null || echo '{"status":"error"}')

    local status
    status=$(echo "$result" | jq -r '.status' 2>/dev/null || echo "error")

    if [[ "$status" == "success" ]]; then
        local value
        value=$(echo "$result" | jq -r '.data.result[0].value[1] // "N/A"' 2>/dev/null || echo "N/A")
        echo "  **$desc:** \`$value\`"
    else
        echo "  **$desc:** *query failed*"
    fi
}

# --- Banner ------------------------------------------------------------------
echo ""
echo "=============================================="
echo "  MiniURL Canary — Capture Baseline Metrics"
echo "=============================================="
echo "  Prometheus URL:  $PROMETHEUS_URL"
echo "  Output:          $OUTPUT_FILE"
echo "  Dry Run:         $DRY_RUN"
echo "=============================================="
echo ""

# Check Prometheus connectivity
if [[ "$DRY_RUN" != "true" ]]; then
    info "Checking Prometheus connectivity..."
    if curl -s --max-time 5 "${PROMETHEUS_URL}/api/v1/status/buildinfo" &>/dev/null; then
        pass "Prometheus is reachable at $PROMETHEUS_URL"
    else
        warn "Cannot reach Prometheus at $PROMETHEUS_URL"
        warn "Attempting port-forward..."

        PROM_POD=$(kubectl get pods -n "$MONITORING_NS" -l "app=prometheus" -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || echo "")
        if [[ -n "$PROM_POD" ]]; then
            info "Port-forwarding to Prometheus pod: $PROM_POD"
            kubectl port-forward -n "$MONITORING_NS" "$PROM_POD" 9090:9090 &>/dev/null &
            PF_PID=$!
            sleep 3
            PROMETHEUS_URL="http://localhost:9090"

            if curl -s --max-time 5 "${PROMETHEUS_URL}/api/v1/status/buildinfo" &>/dev/null; then
                pass "Prometheus reachable via port-forward"
            else
                fail "Still cannot reach Prometheus. Metrics will be marked as unavailable."
            fi
        else
            fail "No Prometheus pod found. Cannot capture baseline."
        fi
    fi
fi

# --- Generate Report ---------------------------------------------------------
info "Generating baseline metrics report..."

mkdir -p "$(dirname "$OUTPUT_FILE")"

{
    echo "# MiniURL Baseline Metrics"
    echo ""
    echo "**Captured:** $TIMESTAMP (UTC)"
    echo "**Prometheus:** $PROMETHEUS_URL"
    echo "**Namespace:** $NAMESPACE"
    echo ""
    echo "---"
    echo ""
    echo "## Redirect Service"
    echo ""

    prom_query \
        "sum(rate(http_server_requests_seconds_count{app=\"redirect-service\",status=~\"5..\"}[5m])) / sum(rate(http_server_requests_seconds_count{app=\"redirect-service\"}[5m]))" \
        "5xx Error Rate (5m)"

    prom_query \
        "histogram_quantile(0.95, rate(http_server_requests_seconds_bucket{app=\"redirect-service\",uri=~\"/r/.*\"}[5m]))" \
        "P95 Latency (5m, seconds)"

    prom_query \
        "histogram_quantile(0.99, rate(http_server_requests_seconds_bucket{app=\"redirect-service\",uri=~\"/r/.*\"}[5m]))" \
        "P99 Latency (5m, seconds)"

    prom_query \
        "rate(redirect_redis_fallback_total[5m])" \
        "Redis Fallback Rate (per second)"

    prom_query \
        "sum(rate(http_server_requests_seconds_count{app=\"redirect-service\",uri=~\"/r/.*\"}[5m]))" \
        "Request Rate (per second)"

    echo ""
    echo "## Outbox / Kafka"
    echo ""

    prom_query \
        "outbox_events_unprocessed{service=\"url-service\"}" \
        "URL Service — Unprocessed Outbox Events"

    prom_query \
        "outbox_events_unprocessed{service=\"identity-service\"}" \
        "Identity Service — Unprocessed Outbox Events"

    prom_query \
        "outbox_events_age_seconds{service=\"url-service\"}" \
        "URL Service — Oldest Event Age (seconds)"

    prom_query \
        "outbox_events_age_seconds{service=\"identity-service\"}" \
        "Identity Service — Oldest Event Age (seconds)"

    prom_query \
        "sum(kafka_consumer_fetch_manager_records_lag)" \
        "Kafka Consumer Lag (total records)"

    echo ""
    echo "## JWKS / Authentication"
    echo ""

    prom_query \
        "probe_success{job=\"blackbox-jwks\"}" \
        "JWKS Probe Success (1=up, 0=down)"

    prom_query \
        "sum(rate(http_server_requests_seconds_count{app=\"identity-service\",uri=~\"/api/auth/.*\",status=~\"4..|5..\"}[5m])) / sum(rate(http_server_requests_seconds_count{app=\"identity-service\",uri=~\"/api/auth/.*\"}[5m]))" \
        "Auth Failure Rate (5m)"

    echo ""
    echo "## Circuit Breakers"
    echo ""

    prom_query \
        "resilience4j_circuitbreaker_state{name=\"emailService\"}" \
        "EmailService Circuit Breaker State (0=CLOSED, 1=OPEN, 2=HALF_OPEN)"

    echo ""
    echo "## Database Connections"
    echo ""

    prom_query \
        "hikaricp_connections_active / hikaricp_connections_max" \
        "Connection Pool Utilization"

    echo ""
    echo "---"
    echo ""
    echo "## Notes"
    echo ""
    echo "- Metrics marked as *query failed* or *N/A* indicate the metric is not yet being emitted."
    echo "- Compare these values against canary-phase metrics to detect regressions."
    echo "- Rollback triggers are defined in [canary-alerts.yaml](../k8s/infrastructure/canary-alerts.yaml)."
    echo ""
} > "$OUTPUT_FILE"

pass "Baseline metrics saved to: $OUTPUT_FILE"

# Cleanup port-forward
if [[ -n "${PF_PID:-}" ]]; then
    kill $PF_PID 2>/dev/null || true
fi

# --- Summary -----------------------------------------------------------------
echo ""
echo "=============================================="
echo -e "${GREEN}  BASELINE METRICS CAPTURED${NC}"
echo "=============================================="
echo ""
echo "Report: $OUTPUT_FILE"
echo ""
echo "Next step: ./scripts/deploy/start-canary.sh"
echo ""
