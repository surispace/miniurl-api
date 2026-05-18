# MiniURL Canary Deployment — Automation Package

Automated scripts for deploying, monitoring, and rolling back MiniURL canary deployments.

---

## Quick Start

```bash
# 1. Set required environment variables
export NAMESPACE=miniurl
export MONITORING_NS=monitoring
export CANARY_IMAGE_TAG=v2.1.0-canary
export STABLE_IMAGE_TAG=v2.0.0-stable
export MYSQL_ROOT_PASSWORD="your-secure-password"

# 2. Run preflight checks
./scripts/deploy/preflight.sh

# 3. Bootstrap infrastructure (Redis + Kafka)
./scripts/deploy/bootstrap-infra.sh

# 4. Create secrets (RSA keys, DB creds, SMTP)
./scripts/deploy/create-secrets.sh

# 5. Run database migrations
./scripts/deploy/run-migrations.sh

# 6. Apply monitoring configuration
./scripts/deploy/apply-monitoring.sh

# 7. Capture baseline metrics
./scripts/deploy/capture-baseline.sh

# 8. Start canary (Phase 1 — 10%)
./scripts/deploy/start-canary.sh
```

---

## Script Reference

| Script | Purpose | Must Run Before |
|---|---|---|
| [`preflight.sh`](scripts/deploy/preflight.sh) | Validate tools, namespaces, config files | Everything else |
| [`bootstrap-infra.sh`](scripts/deploy/bootstrap-infra.sh) | Deploy Redis + Kafka via Helm | `run-migrations.sh` |
| [`create-secrets.sh`](scripts/deploy/create-secrets.sh) | Generate RSA keys, create K8s Secrets | Service deployment |
| [`run-migrations.sh`](scripts/deploy/run-migrations.sh) | Run SQL init scripts against MySQL | Service deployment |
| [`apply-monitoring.sh`](scripts/deploy/apply-monitoring.sh) | Apply Prometheus alerts + blackbox-exporter config | `capture-baseline.sh` |
| [`capture-baseline.sh`](scripts/deploy/capture-baseline.sh) | Query Prometheus for baseline metrics | `start-canary.sh` |
| [`start-canary.sh`](scripts/deploy/start-canary.sh) | Orchestrate canary phases (1-4) | After all above |
| [`rollback-canary.sh`](scripts/deploy/rollback-canary.sh) | Emergency rollback to stable | Anytime |

---

## Environment Variables

### Required for All Scripts

| Variable | Default | Description |
|---|---|---|
| `NAMESPACE` | `miniurl` | Kubernetes namespace for the application |
| `MONITORING_NS` | `monitoring` | Kubernetes namespace for Prometheus/Grafana |
| `DRY_RUN` | `false` | Set to `true` to print commands without executing |

### Required for `bootstrap-infra.sh`

| Variable | Default | Description |
|---|---|---|
| `USE_EXISTING_REDIS` | `false` | Skip Redis install if `true` |
| `USE_EXISTING_KAFKA` | `false` | Skip Kafka install if `true` |
| `REDIS_RELEASE_NAME` | `redis` | Helm release name for Redis |
| `KAFKA_RELEASE_NAME` | `kafka` | Helm release name for Kafka |

### Required for `create-secrets.sh`

| Variable | Default | Description |
|---|---|---|
| `JWT_PRIVATE_KEY_PATH` | *(auto-generate)* | Path to existing RSA private key |
| `JWT_PUBLIC_KEY_PATH` | *(auto-generate)* | Path to existing RSA public key |
| `JWT_RSA_KEY_ID` | `miniurl-rsa-key-1` | Key ID for JWKS endpoint |
| `MYSQL_ROOT_PASSWORD` | *(required)* | MySQL root password |
| `SMTP_USERNAME` | *(optional)* | SMTP username |
| `SMTP_PASSWORD` | *(optional)* | SMTP password |

### Required for `run-migrations.sh`

| Variable | Default | Description |
|---|---|---|
| `MYSQL_IDENTITY_HOST` | `mysql-identity` | Identity DB hostname |
| `MYSQL_IDENTITY_PORT` | `3306` | Identity DB port |
| `MYSQL_IDENTITY_USER` | `root` | Identity DB user |
| `MYSQL_IDENTITY_PASSWORD` | *(required)* | Identity DB password |
| `MYSQL_IDENTITY_DB` | `identity_db` | Identity DB name |
| `MYSQL_URL_HOST` | `mysql-url` | URL DB hostname |
| `MYSQL_URL_PORT` | `3306` | URL DB port |
| `MYSQL_URL_USER` | `root` | URL DB user |
| `MYSQL_URL_PASSWORD` | *(required)* | URL DB password |
| `MYSQL_URL_DB` | `url_db` | URL DB name |
| `MYSQL_FEATURE_HOST` | `mysql-feature` | Feature DB hostname |
| `MYSQL_FEATURE_PORT` | `3306` | Feature DB port |
| `MYSQL_FEATURE_USER` | `root` | Feature DB user |
| `MYSQL_FEATURE_PASSWORD` | *(required)* | Feature DB password |
| `MYSQL_FEATURE_DB` | `feature_db` | Feature DB name |

### Required for `capture-baseline.sh`

| Variable | Default | Description |
|---|---|---|
| `PROMETHEUS_URL` | `http://localhost:9090` | Prometheus API URL |

### Required for `start-canary.sh`

| Variable | Default | Description |
|---|---|---|
| `CANARY_IMAGE_TAG` | *(required)* | Docker image tag for canary version |
| `STABLE_IMAGE_TAG` | `latest` | Docker image tag for stable version |
| `PHASE` | `1` | Canary phase: 1, 2, 3, or 4 |
| `INGRESS_HOST` | `api.miniurl.local` | Ingress hostname |

### Required for `rollback-canary.sh`

| Variable | Default | Description |
|---|---|---|
| `STABLE_IMAGE_TAG` | `latest` | Docker image tag to roll back to |

---

## Dry-Run Mode

All scripts support `DRY_RUN=true` to preview commands without executing:

```bash
DRY_RUN=true ./scripts/deploy/preflight.sh
DRY_RUN=true ./scripts/deploy/bootstrap-infra.sh
DRY_RUN=true CANARY_IMAGE_TAG=v2.1.0-canary ./scripts/deploy/start-canary.sh
```

---

## Canary Phases

The canary deployment proceeds through 4 phases:

| Phase | Traffic % | Duration | Approval Gate |
|---|---|---|---|
| **Phase 1** | 10% | 5 min | Human confirms no alerts |
| **Phase 2** | 25% | 10 min | Human confirms no alerts |
| **Phase 3** | 50% | 15 min | Human confirms no alerts |
| **Phase 4** | 100% | — | Full promotion |

Each phase requires human approval before proceeding. The script will print the command for the next phase.

---

## Rollback

If any canary alert fires or unexpected behavior is observed:

```bash
# Immediate rollback to stable
STABLE_IMAGE_TAG=v2.0.0-stable ./scripts/deploy/rollback-canary.sh

# Dry-run to see what would happen
DRY_RUN=true STABLE_IMAGE_TAG=v2.0.0-stable ./scripts/deploy/rollback-canary.sh
```

Rollback triggers (from [`canary-alerts.yaml`](../k8s/infrastructure/canary-alerts.yaml)):

| Trigger | Alert | Threshold |
|---|---|---|
| RT-1 | `CanaryRedirectHighErrorRate` | 5xx > 0.5% for 2m |
| RT-2 | `CanaryRedirectP99LatencyHigh` | P99 > 200ms for 5m |
| RT-3 | `CanaryRedisHighFallbackRate` | Fallback > 10% for 5m |
| RT-4 | `CanaryOutboxBacklogGrowing` | Unprocessed > 50 for 5m |
| RT-5 | `CanaryJwksEndpointDown` | JWKS probe fails for 1m |
| RT-6 | `CanaryCircuitBreakerOpen` | CB open > 30s |
| RT-7 | `CanaryHpaNotScaling` | CPU > 85% without scale-up |
| RT-8 | `CanaryDbConnectionPoolExhaustion` | Pool > 90% for 2m |

---

## What Still Requires Human Approval

1. **Phase transitions** — Each canary phase requires manual confirmation after monitoring
2. **Initial infrastructure provisioning** — MySQL instances must be deployed separately (not automated — use cloud provider or Helm)
3. **DNS/Ingress configuration** — External DNS and TLS certificates are environment-specific
4. **SMTP credentials** — Must be obtained from your email provider
5. **GitOps integration** — If using ArgoCD/Flux, the canary workflow should be integrated with your GitOps pipeline
6. **Secrets rotation** — RSA keys and DB passwords should be rotated per your security policy

---

## Idempotency

All scripts are designed to be re-run safely:

- `bootstrap-infra.sh` checks if Helm releases already exist before installing
- `create-secrets.sh` checks for existing secrets and asks before overwriting
- `run-migrations.sh` uses `CREATE TABLE IF NOT EXISTS` patterns (from SQL scripts)
- `apply-monitoring.sh` uses `kubectl apply` which is idempotent
- `start-canary.sh` uses `kubectl set image` which is idempotent

---

## Troubleshooting

### "Helm repo 'bitnami' not found"
```bash
helm repo add bitnami https://charts.bitnami.com/bitnami
helm repo update
```

### "Cannot reach Prometheus"
```bash
# Port-forward to Prometheus
kubectl port-forward -n monitoring svc/prometheus 9090:9090

# Then re-run with:
PROMETHEUS_URL=http://localhost:9090 ./scripts/deploy/capture-baseline.sh
```

### "mysql client not found"
The scripts will automatically fall back to `kubectl exec` into MySQL pods. Ensure MySQL pods are running in the namespace.

### "Permission denied" when running scripts
```bash
chmod +x scripts/deploy/*.sh
```
