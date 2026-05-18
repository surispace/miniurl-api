#!/usr/bin/env bash
# =============================================================================
# MiniURL Canary Deployment — Bootstrap Infrastructure (Redis + Kafka)
# =============================================================================
# Deploys Redis and Kafka via Helm (Bitnami charts) or uses existing instances.
#
# Usage:
#   ./scripts/deploy/bootstrap-infra.sh
#   USE_EXISTING_REDIS=true ./scripts/deploy/bootstrap-infra.sh
#   USE_EXISTING_KAFKA=true ./scripts/deploy/bootstrap-infra.sh
#   DRY_RUN=true ./scripts/deploy/bootstrap-infra.sh
#
# Required env vars:
#   NAMESPACE              — Kubernetes namespace (default: miniurl)
#   USE_EXISTING_REDIS     — Skip Redis install if "true" (default: false)
#   USE_EXISTING_KAFKA     — Skip Kafka install if "true" (default: false)
#   REDIS_RELEASE_NAME     — Helm release name for Redis (default: redis)
#   KAFKA_RELEASE_NAME     — Helm release name for Kafka (default: kafka)
#   DRY_RUN                — If "true", print commands only (default: false)
# =============================================================================

set -euo pipefail

# --- Configuration -----------------------------------------------------------
NAMESPACE="${NAMESPACE:-miniurl}"
USE_EXISTING_REDIS="${USE_EXISTING_REDIS:-false}"
USE_EXISTING_KAFKA="${USE_EXISTING_KAFKA:-false}"
REDIS_RELEASE_NAME="${REDIS_RELEASE_NAME:-redis}"
KAFKA_RELEASE_NAME="${KAFKA_RELEASE_NAME:-kafka}"
DRY_RUN="${DRY_RUN:-false}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# --- Color output ------------------------------------------------------------
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
pass() { echo -e "${GREEN}[PASS]${NC} $*"; }
fail() { echo -e "${RED}[FAIL]${NC} $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
info() { echo -e "[INFO] $*"; }

# --- Helpers -----------------------------------------------------------------
run() {
    if [[ "$DRY_RUN" == "true" ]]; then
        info "[DRY-RUN] $*"
    else
        info "Running: $*"
        eval "$@"
    fi
}

wait_for_pods() {
    local label="$1"
    local timeout="${2:-300}"
    info "Waiting for pods with label '$label' (timeout: ${timeout}s)..."
    if [[ "$DRY_RUN" == "true" ]]; then
        info "[DRY-RUN] kubectl wait --for=condition=Ready pods -l $label -n $NAMESPACE --timeout=${timeout}s"
        return
    fi
    kubectl wait --for=condition=Ready pods -l "$label" -n "$NAMESPACE" --timeout="${timeout}s"
}

# --- Banner ------------------------------------------------------------------
echo ""
echo "=============================================="
echo "  MiniURL Canary — Bootstrap Infrastructure"
echo "=============================================="
echo "  Namespace:       $NAMESPACE"
echo "  Use Existing Redis: $USE_EXISTING_REDIS"
echo "  Use Existing Kafka: $USE_EXISTING_KAFKA"
echo "  Dry Run:         $DRY_RUN"
echo "=============================================="
echo ""

# --- Ensure namespace exists -------------------------------------------------
if ! kubectl get namespace "$NAMESPACE" &>/dev/null; then
    info "Creating namespace: $NAMESPACE"
    run "kubectl create namespace $NAMESPACE"
fi

# =============================================================================
# REDIS
# =============================================================================
echo ""
echo "--- Redis ---"

if [[ "$USE_EXISTING_REDIS" == "true" ]]; then
    info "Skipping Redis install (USE_EXISTING_REDIS=true)"
    info "Verifying existing Redis connectivity..."

    REDIS_SVC="${REDIS_RELEASE_NAME}-master"
    if kubectl get svc "$REDIS_SVC" -n "$NAMESPACE" &>/dev/null; then
        pass "Redis service '$REDIS_SVC' exists in namespace '$NAMESPACE'"
    else
        warn "Redis service '$REDIS_SVC' not found. Checking for alternative names..."
        kubectl get svc -n "$NAMESPACE" | grep -i redis || warn "No Redis service found."
    fi
else
    # Check if already installed
    if helm status "$REDIS_RELEASE_NAME" -n "$NAMESPACE" &>/dev/null; then
        info "Redis release '$REDIS_RELEASE_NAME' already exists. Skipping install."
        pass "Redis is already deployed."
    else
        info "Installing Redis via Helm (Bitnami)..."
        run "helm upgrade --install $REDIS_RELEASE_NAME bitnami/redis \
            --namespace $NAMESPACE \
            --set architecture=standalone \
            --set auth.enabled=false \
            --set master.persistence.enabled=false \
            --wait \
            --timeout 10m"

        pass "Redis installed successfully."
    fi

    # Wait for Redis pod
    wait_for_pods "app.kubernetes.io/name=redis" 300
    pass "Redis pods are ready."
fi

# Verify Redis PING
echo ""
info "Verifying Redis PING..."
REDIS_POD=$(kubectl get pods -n "$NAMESPACE" -l "app.kubernetes.io/name=redis" -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || echo "")

if [[ -n "$REDIS_POD" ]]; then
    if [[ "$DRY_RUN" == "true" ]]; then
        info "[DRY-RUN] kubectl exec -n $NAMESPACE $REDIS_POD -- redis-cli PING"
    else
        PING_RESULT=$(kubectl exec -n "$NAMESPACE" "$REDIS_POD" -- redis-cli PING 2>&1 || echo "FAILED")
        if [[ "$PING_RESULT" == "PONG" ]]; then
            pass "Redis PING: $PING_RESULT"
        else
            fail "Redis PING failed: $PING_RESULT"
        fi
    fi
else
    warn "Could not find Redis pod. Trying service-based verification..."
    REDIS_SVC="${REDIS_RELEASE_NAME}-master"
    if kubectl get svc "$REDIS_SVC" -n "$NAMESPACE" &>/dev/null; then
        REDIS_HOST=$(kubectl get svc "$REDIS_SVC" -n "$NAMESPACE" -o jsonpath='{.spec.clusterIP}')
        info "Redis service cluster IP: $REDIS_HOST"
        pass "Redis service is available at $REDIS_SVC.$NAMESPACE.svc.cluster.local:6379"
    fi
fi

# =============================================================================
# KAFKA
# =============================================================================
echo ""
echo "--- Kafka ---"

if [[ "$USE_EXISTING_KAFKA" == "true" ]]; then
    info "Skipping Kafka install (USE_EXISTING_KAFKA=true)"
    info "Verifying existing Kafka..."

    if kubectl get svc "$KAFKA_RELEASE_NAME" -n "$NAMESPACE" &>/dev/null; then
        pass "Kafka service '$KAFKA_RELEASE_NAME' exists in namespace '$NAMESPACE'"
    else
        warn "Kafka service '$KAFKA_RELEASE_NAME' not found. Checking for alternatives..."
        kubectl get svc -n "$NAMESPACE" | grep -i kafka || warn "No Kafka service found."
    fi
else
    # Check if already installed
    if helm status "$KAFKA_RELEASE_NAME" -n "$NAMESPACE" &>/dev/null; then
        info "Kafka release '$KAFKA_RELEASE_NAME' already exists. Skipping install."
        pass "Kafka is already deployed."
    else
        info "Installing Kafka via Helm (Bitnami)..."
        run "helm upgrade --install $KAFKA_RELEASE_NAME bitnami/kafka \
            --namespace $NAMESPACE \
            --set replicaCount=1 \
            --set listeners.client.protocol=PLAINTEXT \
            --set allowPlaintextListener=true \
            --set deleteTopicEnable=true \
            --set autoCreateTopicsEnable=true \
            --wait \
            --timeout 10m"

        pass "Kafka installed successfully."
    fi

    # Wait for Kafka pod
    wait_for_pods "app.kubernetes.io/name=kafka" 300
    pass "Kafka pods are ready."
fi

# =============================================================================
# CREATE KAFKA TOPICS
# =============================================================================
echo ""
echo "--- Kafka Topics ---"

KAFKA_POD=$(kubectl get pods -n "$NAMESPACE" -l "app.kubernetes.io/name=kafka" -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || echo "")

TOPICS=("notifications" "click-events" "general-events")

if [[ -n "$KAFKA_POD" ]]; then
    for topic in "${TOPICS[@]}"; do
        info "Ensuring topic '$topic' exists..."

        if [[ "$DRY_RUN" == "true" ]]; then
            info "[DRY-RUN] kubectl exec -n $NAMESPACE $KAFKA_POD -- kafka-topics.sh --create --if-not-exists --topic $topic --bootstrap-server localhost:9092 --partitions 3 --replication-factor 1"
        else
            kubectl exec -n "$NAMESPACE" "$KAFKA_POD" -- \
                kafka-topics.sh --create --if-not-exists \
                --topic "$topic" \
                --bootstrap-server localhost:9092 \
                --partitions 3 \
                --replication-factor 1 2>&1 || warn "Topic '$topic' may already exist (this is OK)."
        fi
    done

    # Verify topics
    if [[ "$DRY_RUN" != "true" ]]; then
        info "Verifying topics..."
        TOPIC_LIST=$(kubectl exec -n "$NAMESPACE" "$KAFKA_POD" -- \
            kafka-topics.sh --list --bootstrap-server localhost:9092 2>&1 || echo "")
        for topic in "${TOPICS[@]}"; do
            if echo "$TOPIC_LIST" | grep -q "^$topic$"; then
                pass "Topic '$topic' exists"
            else
                fail "Topic '$topic' NOT found"
            fi
        done
    fi
else
    warn "No Kafka pod found. Topics must be created manually:"
    for topic in "${TOPICS[@]}"; do
        warn "  kafka-topics.sh --create --topic $topic --bootstrap-server <broker>:9092 --partitions 3 --replication-factor 1"
    done
fi

# =============================================================================
# SUMMARY
# =============================================================================
echo ""
echo "=============================================="
echo -e "${GREEN}  INFRASTRUCTURE BOOTSTRAP COMPLETE${NC}"
echo "=============================================="
echo ""
echo "Infrastructure ready:"
echo "  Redis:  $REDIS_RELEASE_NAME-master.$NAMESPACE.svc.cluster.local:6379"
echo "  Kafka:  $KAFKA_RELEASE_NAME.$NAMESPACE.svc.cluster.local:9092"
echo ""
echo "Next step: ./scripts/deploy/create-secrets.sh"
echo ""
