#!/usr/bin/env bash
# =============================================================================
# MiniURL Canary Deployment — Run Database Migrations
# =============================================================================
# Runs SQL migration scripts against MySQL instances.
# Supports both direct mysql client and kubectl exec fallback.
#
# Usage:
#   ./scripts/deploy/run-migrations.sh
#   MYSQL_HOST=localhost MYSQL_PORT=3306 ./scripts/deploy/run-migrations.sh
#   DRY_RUN=true ./scripts/deploy/run-migrations.sh
#
# Required env vars:
#   NAMESPACE              — Kubernetes namespace (default: miniurl)
#   MYSQL_IDENTITY_HOST    — Identity DB host (default: mysql-identity)
#   MYSQL_IDENTITY_PORT    — Identity DB port (default: 3306)
#   MYSQL_IDENTITY_USER    — Identity DB user (default: root)
#   MYSQL_IDENTITY_PASSWORD— Identity DB password (required)
#   MYSQL_IDENTITY_DB      — Identity DB name (default: identity_db)
#   MYSQL_URL_HOST         — URL DB host (default: mysql-url)
#   MYSQL_URL_PORT         — URL DB port (default: 3306)
#   MYSQL_URL_USER         — URL DB user (default: root)
#   MYSQL_URL_PASSWORD     — URL DB password (required)
#   MYSQL_URL_DB           — URL DB name (default: url_db)
#   MYSQL_FEATURE_HOST     — Feature DB host (default: mysql-feature)
#   MYSQL_FEATURE_PORT     — Feature DB port (default: 3306)
#   MYSQL_FEATURE_USER     — Feature DB user (default: root)
#   MYSQL_FEATURE_PASSWORD — Feature DB password (required)
#   MYSQL_FEATURE_DB       — Feature DB name (default: feature_db)
#   DRY_RUN                — If "true", print commands only (default: false)
# =============================================================================

set -euo pipefail

# --- Configuration -----------------------------------------------------------
NAMESPACE="${NAMESPACE:-miniurl}"
DRY_RUN="${DRY_RUN:-false}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# DB connection configs
MYSQL_IDENTITY_HOST="${MYSQL_IDENTITY_HOST:-mysql-identity}"
MYSQL_IDENTITY_PORT="${MYSQL_IDENTITY_PORT:-3306}"
MYSQL_IDENTITY_USER="${MYSQL_IDENTITY_USER:-root}"
MYSQL_IDENTITY_PASSWORD="${MYSQL_IDENTITY_PASSWORD:-}"
MYSQL_IDENTITY_DB="${MYSQL_IDENTITY_DB:-identity_db}"

MYSQL_URL_HOST="${MYSQL_URL_HOST:-mysql-url}"
MYSQL_URL_PORT="${MYSQL_URL_PORT:-3306}"
MYSQL_URL_USER="${MYSQL_URL_USER:-root}"
MYSQL_URL_PASSWORD="${MYSQL_URL_PASSWORD:-}"
MYSQL_URL_DB="${MYSQL_URL_DB:-url_db}"

MYSQL_FEATURE_HOST="${MYSQL_FEATURE_HOST:-mysql-feature}"
MYSQL_FEATURE_PORT="${MYSQL_FEATURE_PORT:-3306}"
MYSQL_FEATURE_USER="${MYSQL_FEATURE_USER:-root}"
MYSQL_FEATURE_PASSWORD="${MYSQL_FEATURE_PASSWORD:-}"
MYSQL_FEATURE_DB="${MYSQL_FEATURE_DB:-feature_db}"

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

# Run SQL via kubectl exec into a MySQL pod
run_sql_kubectl() {
    local pod_label="$1"
    local db_name="$2"
    local sql_file="$3"
    local password="$4"

    local pod
    pod=$(kubectl get pods -n "$NAMESPACE" -l "$pod_label" -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || echo "")

    if [[ -z "$pod" ]]; then
        fail "No MySQL pod found with label '$pod_label' in namespace '$NAMESPACE'"
        return 1
    fi

    info "Running $sql_file on pod $pod (db: $db_name)..."
    if [[ "$DRY_RUN" == "true" ]]; then
        info "[DRY-RUN] kubectl exec -n $NAMESPACE $pod -- mysql -u root -p\$MYSQL_PWD $db_name < $sql_file"
    else
        kubectl exec -i -n "$NAMESPACE" "$pod" -- \
            env MYSQL_PWD="$password" mysql -u root "$db_name" < "$sql_file"
        pass "Migration applied: $sql_file → $db_name"
    fi
}

# Run SQL via mysql client
run_sql_client() {
    local host="$1"
    local port="$2"
    local user="$3"
    local password="$4"
    local db_name="$5"
    local sql_file="$6"

    info "Running $sql_file on $host:$port/$db_name..."
    if [[ "$DRY_RUN" == "true" ]]; then
        info "[DRY-RUN] MYSQL_PWD=$password mysql -h $host -P $port -u $user $db_name < $sql_file"
    else
        MYSQL_PWD="$password" mysql -h "$host" -P "$port" -u "$user" "$db_name" < "$sql_file"
        pass "Migration applied: $sql_file → $host:$port/$db_name"
    fi
}

# Determine execution method
run_migration() {
    local host="$1"
    local port="$2"
    local user="$3"
    local password="$4"
    local db_name="$5"
    local sql_file="$6"
    local pod_label="${7:-}"

    if [[ -z "$password" ]]; then
        fail "Password not set for $db_name. Set MYSQL_*_PASSWORD env var."
        return 1
    fi

    if command -v mysql &>/dev/null; then
        run_sql_client "$host" "$port" "$user" "$password" "$db_name" "$sql_file"
    elif [[ -n "$pod_label" ]]; then
        run_sql_kubectl "$pod_label" "$db_name" "$sql_file" "$password"
    else
        fail "Neither mysql client nor pod label available for $db_name."
        return 1
    fi
}

# Verify tables exist
verify_tables() {
    local host="$1"
    local port="$2"
    local user="$3"
    local password="$4"
    local db_name="$5"
    local expected_tables="$6"

    info "Verifying tables in $db_name..."

    if [[ "$DRY_RUN" == "true" ]]; then
        info "[DRY-RUN] Would verify tables: $expected_tables"
        return
    fi

    local tables
    if command -v mysql &>/dev/null; then
        tables=$(MYSQL_PWD="$password" mysql -h "$host" -P "$port" -u "$user" "$db_name" -N -e "SHOW TABLES;" 2>/dev/null || echo "")
    else
        local pod
        pod=$(kubectl get pods -n "$NAMESPACE" -l "app=mysql-${db_name%%_db}" -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || echo "")
        if [[ -n "$pod" ]]; then
            tables=$(kubectl exec -n "$NAMESPACE" "$pod" -- env MYSQL_PWD="$password" mysql -u "$user" "$db_name" -N -e "SHOW TABLES;" 2>/dev/null || echo "")
        fi
    fi

    if [[ -n "$tables" ]]; then
        pass "Tables in $db_name: $(echo "$tables" | tr '\n' ' ')"
    else
        warn "Could not verify tables in $db_name."
    fi
}

# --- Banner ------------------------------------------------------------------
echo ""
echo "=============================================="
echo "  MiniURL Canary — Run Database Migrations"
echo "=============================================="
echo "  Namespace:       $NAMESPACE"
echo "  Dry Run:         $DRY_RUN"
echo "=============================================="
echo ""

# =============================================================================
# IDENTITY DB
# =============================================================================
echo ""
echo "--- Identity Database ---"

IDENTITY_SQL="$REPO_ROOT/scripts/init-identity-db.sql"
if [[ -f "$IDENTITY_SQL" ]]; then
    run_migration \
        "$MYSQL_IDENTITY_HOST" "$MYSQL_IDENTITY_PORT" \
        "$MYSQL_IDENTITY_USER" "$MYSQL_IDENTITY_PASSWORD" \
        "$MYSQL_IDENTITY_DB" "$IDENTITY_SQL" \
        "app=mysql-identity"
    verify_tables \
        "$MYSQL_IDENTITY_HOST" "$MYSQL_IDENTITY_PORT" \
        "$MYSQL_IDENTITY_USER" "$MYSQL_IDENTITY_PASSWORD" \
        "$MYSQL_IDENTITY_DB" "users,roles,user_roles"
else
    warn "Identity DB init script not found: $IDENTITY_SQL"
fi

# =============================================================================
# URL DB
# =============================================================================
echo ""
echo "--- URL Database ---"

URL_SQL="$REPO_ROOT/scripts/init-url-db.sql"
if [[ -f "$URL_SQL" ]]; then
    run_migration \
        "$MYSQL_URL_HOST" "$MYSQL_URL_PORT" \
        "$MYSQL_URL_USER" "$MYSQL_URL_PASSWORD" \
        "$MYSQL_URL_DB" "$URL_SQL" \
        "app=mysql-url"
    verify_tables \
        "$MYSQL_URL_HOST" "$MYSQL_URL_PORT" \
        "$MYSQL_URL_USER" "$MYSQL_URL_PASSWORD" \
        "$MYSQL_URL_DB" "urls,url_usage_limits"
else
    warn "URL DB init script not found: $URL_SQL"
fi

# =============================================================================
# FEATURE DB
# =============================================================================
echo ""
echo "--- Feature Database ---"

FEATURE_SQL="$REPO_ROOT/scripts/init-feature-db.sql"
if [[ -f "$FEATURE_SQL" ]]; then
    run_migration \
        "$MYSQL_FEATURE_HOST" "$MYSQL_FEATURE_PORT" \
        "$MYSQL_FEATURE_USER" "$MYSQL_FEATURE_PASSWORD" \
        "$MYSQL_FEATURE_DB" "$FEATURE_SQL" \
        "app=mysql-feature"
    verify_tables \
        "$MYSQL_FEATURE_HOST" "$MYSQL_FEATURE_PORT" \
        "$MYSQL_FEATURE_USER" "$MYSQL_FEATURE_PASSWORD" \
        "$MYSQL_FEATURE_DB" "feature_flags,global_flags"
else
    warn "Feature DB init script not found: $FEATURE_SQL"
fi

# =============================================================================
# MAIN DB (monolith fallback)
# =============================================================================
echo ""
echo "--- Main Database (Monolith) ---"

MAIN_SQL="$REPO_ROOT/scripts/init-db.sql"
if [[ -f "$MAIN_SQL" ]]; then
    info "Main DB init script found. Run separately if using monolith:"
    info "  MYSQL_PWD=\$MYSQL_ROOT_PASSWORD mysql -h <host> -u root miniurldb < $MAIN_SQL"
else
    info "Main DB init script not found (not needed for microservices)."
fi

# =============================================================================
# SUMMARY
# =============================================================================
echo ""
echo "=============================================="
echo -e "${GREEN}  DATABASE MIGRATIONS COMPLETE${NC}"
echo "=============================================="
echo ""
echo "Next step: ./scripts/deploy/apply-monitoring.sh"
echo ""
