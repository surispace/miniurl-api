#!/usr/bin/env bash
# =============================================================================
# MiniURL Canary Deployment — Create Kubernetes Secrets
# =============================================================================
# Generates RSA key pair (if not provided) and creates the jwt-rsa-keys Secret.
# Also creates db-secrets and smtp-credentials if env vars are set.
#
# Usage:
#   ./scripts/deploy/create-secrets.sh
#   JWT_PRIVATE_KEY_PATH=./private.pem JWT_PUBLIC_KEY_PATH=./public.pem ./scripts/deploy/create-secrets.sh
#   DRY_RUN=true ./scripts/deploy/create-secrets.sh
#
# Required env vars:
#   NAMESPACE              — Kubernetes namespace (default: miniurl)
#   JWT_PRIVATE_KEY_PATH   — Path to existing RSA private key (optional)
#   JWT_PUBLIC_KEY_PATH    — Path to existing RSA public key (optional)
#   JWT_RSA_KEY_ID         — Key ID for JWKS (default: miniurl-rsa-key-1)
#   MYSQL_ROOT_PASSWORD    — MySQL root password for db-secrets (optional)
#   SMTP_USERNAME          — SMTP username (optional)
#   SMTP_PASSWORD          — SMTP password (optional)
#   DRY_RUN                — If "true", print commands only (default: false)
# =============================================================================

set -euo pipefail

# --- Configuration -----------------------------------------------------------
NAMESPACE="${NAMESPACE:-miniurl}"
JWT_PRIVATE_KEY_PATH="${JWT_PRIVATE_KEY_PATH:-}"
JWT_PUBLIC_KEY_PATH="${JWT_PUBLIC_KEY_PATH:-}"
JWT_RSA_KEY_ID="${JWT_RSA_KEY_ID:-miniurl-rsa-key-1}"
MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-}"
SMTP_USERNAME="${SMTP_USERNAME:-}"
SMTP_PASSWORD="${SMTP_PASSWORD:-}"
DRY_RUN="${DRY_RUN:-false}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# Temp directory for generated keys
TMPDIR="${TMPDIR:-/tmp}"
KEY_DIR="${KEY_DIR:-$TMPDIR/miniurl-keys-$$}"

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
        eval "$@"
    fi
}

cleanup() {
    if [[ -d "$KEY_DIR" ]] && [[ "$DRY_RUN" != "true" ]]; then
        rm -rf "$KEY_DIR"
        info "Cleaned up temporary key directory: $KEY_DIR"
    fi
}
trap cleanup EXIT

# --- Banner ------------------------------------------------------------------
echo ""
echo "=============================================="
echo "  MiniURL Canary — Create Secrets"
echo "=============================================="
echo "  Namespace:       $NAMESPACE"
echo "  Dry Run:         $DRY_RUN"
echo "=============================================="
echo ""

# Ensure namespace exists
if ! kubectl get namespace "$NAMESPACE" &>/dev/null; then
    info "Creating namespace: $NAMESPACE"
    run "kubectl create namespace $NAMESPACE"
fi

# =============================================================================
# JWT RSA KEYS
# =============================================================================
echo ""
echo "--- JWT RSA Keys ---"

# Check if secret already exists
if kubectl get secret jwt-rsa-keys -n "$NAMESPACE" &>/dev/null; then
    info "Secret 'jwt-rsa-keys' already exists in namespace '$NAMESPACE'."
    read -r -p "Overwrite? [y/N] " RESPONSE
    if [[ ! "$RESPONSE" =~ ^[Yy]$ ]]; then
        info "Skipping JWT RSA key creation."
    else
        info "Will overwrite existing secret."
        run "kubectl delete secret jwt-rsa-keys -n $NAMESPACE"
    fi
fi

# Determine key source
if [[ -n "$JWT_PRIVATE_KEY_PATH" ]] && [[ -n "$JWT_PUBLIC_KEY_PATH" ]]; then
    # Use provided keys
    if [[ ! -f "$JWT_PRIVATE_KEY_PATH" ]]; then
        fail "Private key not found: $JWT_PRIVATE_KEY_PATH"
        exit 1
    fi
    if [[ ! -f "$JWT_PUBLIC_KEY_PATH" ]]; then
        fail "Public key not found: $JWT_PUBLIC_KEY_PATH"
        exit 1
    fi
    info "Using provided RSA keys:"
    info "  Private: $JWT_PRIVATE_KEY_PATH"
    info "  Public:  $JWT_PUBLIC_KEY_PATH"

    PRIV_KEY="$JWT_PRIVATE_KEY_PATH"
    PUB_KEY="$JWT_PUBLIC_KEY_PATH"
else
    # Generate new keys
    info "No key paths provided. Generating new RSA 2048-bit key pair..."
    mkdir -p "$KEY_DIR"

    PRIV_KEY="$KEY_DIR/private.pem"
    PUB_KEY="$KEY_DIR/public.pem"

    if [[ "$DRY_RUN" == "true" ]]; then
        info "[DRY-RUN] openssl genpkey -algorithm RSA -out $PRIV_KEY -pkeyopt rsa_keygen_bits:2048"
        info "[DRY-RUN] openssl rsa -pubout -in $PRIV_KEY -out $PUB_KEY"
    else
        openssl genpkey -algorithm RSA -out "$PRIV_KEY" -pkeyopt rsa_keygen_bits:2048
        openssl rsa -pubout -in "$PRIV_KEY" -out "$PUB_KEY"
        pass "RSA key pair generated."
        info "  Private key: $PRIV_KEY"
        info "  Public key:  $PUB_KEY"
    fi
fi

# Create the secret
info "Creating Kubernetes secret 'jwt-rsa-keys'..."
run "kubectl create secret generic jwt-rsa-keys \
    --namespace $NAMESPACE \
    --from-file=private.pem=$PRIV_KEY \
    --from-file=public.pem=$PUB_KEY \
    --from-literal=key-id=$JWT_RSA_KEY_ID \
    --dry-run=client -o yaml | kubectl apply -f -"

pass "Secret 'jwt-rsa-keys' created/updated in namespace '$NAMESPACE'."

# Verify (don't print private key)
if [[ "$DRY_RUN" != "true" ]]; then
    if kubectl get secret jwt-rsa-keys -n "$NAMESPACE" &>/dev/null; then
        SECRET_KEYS=$(kubectl get secret jwt-rsa-keys -n "$NAMESPACE" -o jsonpath='{.data}' | jq 'keys')
        info "Secret keys: $SECRET_KEYS"
        pass "Secret 'jwt-rsa-keys' verified."
    fi
fi

# =============================================================================
# DB SECRETS
# =============================================================================
echo ""
echo "--- Database Secrets ---"

if kubectl get secret db-secrets -n "$NAMESPACE" &>/dev/null; then
    info "Secret 'db-secrets' already exists. Skipping."
else
    if [[ -n "$MYSQL_ROOT_PASSWORD" ]]; then
        info "Creating 'db-secrets' from MYSQL_ROOT_PASSWORD env var..."
        run "kubectl create secret generic db-secrets \
            --namespace $NAMESPACE \
            --from-literal=MYSQL_ROOT_PASSWORD=$MYSQL_ROOT_PASSWORD \
            --dry-run=client -o yaml | kubectl apply -f -"
        pass "Secret 'db-secrets' created."
    else
        warn "MYSQL_ROOT_PASSWORD not set. Skipping db-secrets creation."
        warn "Create manually: kubectl create secret generic db-secrets -n $NAMESPACE --from-literal=MYSQL_ROOT_PASSWORD=<password>"
    fi
fi

# =============================================================================
# SMTP CREDENTIALS
# =============================================================================
echo ""
echo "--- SMTP Credentials ---"

if kubectl get secret smtp-credentials -n "$NAMESPACE" &>/dev/null; then
    info "Secret 'smtp-credentials' already exists. Skipping."
else
    if [[ -n "$SMTP_USERNAME" ]] && [[ -n "$SMTP_PASSWORD" ]]; then
        info "Creating 'smtp-credentials' from SMTP_USERNAME/SMTP_PASSWORD env vars..."
        run "kubectl create secret generic smtp-credentials \
            --namespace $NAMESPACE \
            --from-literal=username=$SMTP_USERNAME \
            --from-literal=password=$SMTP_PASSWORD \
            --dry-run=client -o yaml | kubectl apply -f -"
        pass "Secret 'smtp-credentials' created."
    else
        warn "SMTP_USERNAME or SMTP_PASSWORD not set. Skipping smtp-credentials creation."
        warn "Create manually: kubectl create secret generic smtp-credentials -n $NAMESPACE --from-literal=username=<user> --from-literal=password=<pass>"
    fi
fi

# =============================================================================
# SUMMARY
# =============================================================================
echo ""
echo "=============================================="
echo -e "${GREEN}  SECRETS CREATION COMPLETE${NC}"
echo "=============================================="
echo ""
echo "Secrets in namespace '$NAMESPACE':"
if [[ "$DRY_RUN" != "true" ]]; then
    kubectl get secrets -n "$NAMESPACE" 2>/dev/null || warn "Cannot list secrets."
fi
echo ""
echo "Next step: ./scripts/deploy/run-migrations.sh"
echo ""
