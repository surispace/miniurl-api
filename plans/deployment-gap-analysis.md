# MiniURL Deployment Gap Analysis

**Date:** 2026-04-30
**Auditor:** Automated CI/CD Architecture Audit
**Scope:** All deployment assets across `helm/`, `k8s/`, `scripts/deploy/`, `docker-compose.yml`, `Dockerfile*`, `.github/workflows/`, and documentation.

---

## 1. Inventory of Existing Assets

### 1.1 Helm Chart (`helm/miniurl/`)

| File | Purpose | Status |
|---|---|---|
| [`Chart.yaml`](helm/miniurl/Chart.yaml) | Chart metadata (v0.1.0, appVersion 1.0.0-SNAPSHOT) | Present |
| [`values.yaml`](helm/miniurl/values.yaml) | Base config: 8 services, ghcr.io images, HPA defaults, DB hostnames | Present |
| [`values-dev.yaml`](helm/miniurl/values-dev.yaml) | Dev overrides: local images, 1 replica, no HPA | Present |
| [`values-prod.yaml`](helm/miniurl/values-prod.yaml) | Prod overrides: production replicas, HPA enabled | Present |
| [`templates/_helpers.tpl`](helm/miniurl/templates/_helpers.tpl) | Shared labels (`app.kubernetes.io/name`, `part-of: miniurl`) | Present |
| [`templates/deployment.yaml`](helm/miniurl/templates/deployment.yaml) | Iterates `.Values.services`, creates Deployment per service | Present |
| [`templates/service.yaml`](helm/miniurl/templates/service.yaml) | Iterates `.Values.services`, creates Service per service | Present |
| [`templates/configmap.yaml`](helm/miniurl/templates/configmap.yaml) | Single `global-config` ConfigMap from `.Values.globalConfig` | Present |
| [`templates/hpa.yaml`](helm/miniurl/templates/hpa.yaml) | Conditional HPA per service when `hpa.enabled: true` | Present |

**Missing from Helm:**
- No `templates/ingress.yaml` — Ingress only exists in raw k8s manifests
- No `templates/secrets.yaml` — Secrets are managed via `scripts/deploy/create-secrets.sh` only
- No `templates/serviceaccount.yaml` — No RBAC scaffolding
- No `templates/networkpolicy.yaml` — NetworkPolicies exist only in raw k8s
- No `templates/pdb.yaml` — PodDisruptionBudgets exist only in raw k8s
- No `Chart.lock` or dependency declarations for Redis/Kafka (Bitnami)
- No `NOTES.txt` for post-install instructions

### 1.2 Raw Kubernetes Manifests (`k8s/`)

| Path | Content | Overlaps With |
|---|---|---|
| [`k8s/miniurl-all-in-one.yaml`](k8s/miniurl-all-in-one.yaml) | Monolithic 1274-line manifest: Namespace, ConfigMaps, **hardcoded Secrets**, PVCs, MySQL StatefulSets, Redis Deployment, Kafka StatefulSet, all 8 services, HPAs, Ingress | Helm templates, `k8s/services/*`, `k8s/hpa/*` |
| [`k8s/hpa/hpa.yaml`](k8s/hpa/hpa.yaml) | HPAs for api-gateway, redirect-service, url-service, identity-service | `miniurl-all-in-one.yaml` (lines 1126-1231), Helm HPA template |
| [`k8s/infrastructure/global-config.yaml`](k8s/infrastructure/global-config.yaml) | ConfigMap + db-secrets Secret + Prometheus + Grafana deployments | `miniurl-all-in-one.yaml`, Helm configmap template, `k8s/infrastructure/monitoring.yaml` |
| [`k8s/infrastructure/monitoring.yaml`](k8s/infrastructure/monitoring.yaml) | Prometheus server, Grafana, datasource/dashboard ConfigMaps | `k8s/infrastructure/global-config.yaml` (duplicate Prometheus+Grafana) |
| [`k8s/infrastructure/canary-alerts.yaml`](k8s/infrastructure/canary-alerts.yaml) | 18 Prometheus alert rules (8 rollback triggers) for canary | None (unique) |
| [`k8s/infrastructure/prometheus-config.yaml`](k8s/infrastructure/prometheus-config.yaml) | Prometheus scrape config for spring-boot-apps job | None (unique) |
| [`k8s/infrastructure/blackbox-exporter-jwks-config.yaml`](k8s/infrastructure/blackbox-exporter-jwks-config.yaml) | JWKS probe config (comments/instructions, not actual manifests) | None (unique) |
| [`k8s/infrastructure/elk.yaml`](k8s/infrastructure/elk.yaml) | Elasticsearch + Logstash + Kibana + logstash-config ConfigMap | `k8s/infrastructure/elk-config.yaml` (duplicate logstash-config) |
| [`k8s/infrastructure/elk-config.yaml`](k8s/infrastructure/elk-config.yaml) | Elasticsearch, Logstash, Kibana ConfigMaps | `k8s/infrastructure/elk.yaml` (duplicate logstash-config) |
| [`k8s/services/*.yaml`](k8s/services/) | Individual Deployment+Service per microservice (8 files) | `miniurl-all-in-one.yaml`, Helm templates |
| [`k8s/services/network-policies.yaml`](k8s/services/network-policies.yaml) | 8 NetworkPolicies (default-deny, per-service ingress rules) | None (unique) |
| [`k8s/services/pod-disruption-budgets.yaml`](k8s/services/pod-disruption-budgets.yaml) | 8 PDBs (one per service) | None (unique) |

### 1.3 Deployment Scripts (`scripts/deploy/`)

| Script | Purpose | Integrated with CI/CD? |
|---|---|---|
| [`preflight.sh`](scripts/deploy/preflight.sh) | Validates kubectl, helm, openssl, curl, jq; checks namespaces, Helm repos, config files, StorageClass | No |
| [`bootstrap-infra.sh`](scripts/deploy/bootstrap-infra.sh) | Installs Redis + Kafka via Bitnami Helm charts, creates Kafka topics | No |
| [`create-secrets.sh`](scripts/deploy/create-secrets.sh) | Generates RSA key pair, creates `jwt-rsa-keys`, `db-secrets`, `smtp-credentials` Secrets | No |
| [`run-migrations.sh`](scripts/deploy/run-migrations.sh) | Runs SQL init scripts against identity/url/feature MySQL instances | No |
| [`apply-monitoring.sh`](scripts/deploy/apply-monitoring.sh) | Applies canary-alerts.yaml, prometheus-config.yaml, blackbox-exporter config | No |
| [`capture-baseline.sh`](scripts/deploy/capture-baseline.sh) | Queries Prometheus for baseline metrics, writes markdown report | No |
| [`start-canary.sh`](scripts/deploy/start-canary.sh) | Orchestrates 4-phase canary (10%→25%→50%→100%) with human approval gates | No |
| [`rollback-canary.sh`](scripts/deploy/rollback-canary.sh) | Emergency rollback: reverts all services to stable image tag | No |

### 1.4 Database Scripts (`scripts/`)

| Script | Purpose |
|---|---|
| [`init-db.sql`](scripts/init-db.sql) | Main DB init (monolith fallback) |
| [`init-identity-db.sql`](scripts/init-identity-db.sql) | Identity DB: users, roles, user_roles tables |
| [`init-url-db.sql`](scripts/init-url-db.sql) | URL DB: urls, url_usage_limits tables |
| [`init-feature-db.sql`](scripts/init-feature-db.sql) | Feature DB: feature_flags, global_flags tables |
| [`migrate-2fa.sql`](scripts/migrate-2fa.sql) | 2FA migration |
| [`init-db.sh`](scripts/init-db.sh) | Shell wrapper for DB init |
| [`reset-admin-password.sh`](scripts/reset-admin-password.sh) | Admin password reset utility |

### 1.5 Docker Compose (`docker-compose.yml`)

Provides a complete local development environment:
- **Infrastructure:** Zookeeper, Kafka, Redis, 3× MySQL (identity/url/feature)
- **Services:** All 8 microservices built from the multi-stage Dockerfile
- **Monitoring:** Prometheus + Grafana
- **Network:** `miniurl-network` bridge

All services use `SPRING_PROFILES_ACTIVE=dev` and reference each other by container name.

### 1.6 Dockerfiles

| File | Base Image | Java Version | Status |
|---|---|---|---|
| [`Dockerfile`](Dockerfile) | `eclipse-temurin:21-jre-alpine` (runtime), `maven:3-eclipse-temurin-21` (build) | 21 | **Active** — referenced by docker-compose.yml and GHA workflows |
| [`Dockerfile.multi`](Dockerfile.multi) | `eclipse-temurin:17-jre` (runtime), `maven:3.9-eclipse-temurin-17` (build) | 17 | **Stale** — not referenced anywhere |

### 1.7 GitHub Actions Workflows

| Workflow | Trigger | What It Does | Deploys? |
|---|---|---|---|
| [`pr-validation.yml`](.github/workflows/pr-validation.yml) | PR → main/master | Maven build + unit tests, build & push Docker images (tag: `pr-{N}`) to ghcr.io + Docker Hub | No |
| [`main-pipeline.yml`](.github/workflows/main-pipeline.yml) | Push to main/master | Maven build + tests, build & push Docker images (`latest`, `sha-{hash}`, branch name), **Helm deploy with `--atomic`**, bump SNAPSHOT version, Slack notifications | **Yes — directly to production** |
| [`release.yml`](.github/workflows/release.yml) | Tag `v*` or manual dispatch | Multi-arch build (`linux/amd64`, `linux/arm64`), push to ghcr.io + Docker Hub with semver tags, create GitHub issue | No |

### 1.8 Documentation

| Document | Content |
|---|---|
| [`README.md`](README.md) | Architecture overview, tech stack, local setup (docker-compose + mvn spring-boot:run), k8s deploy instructions |
| [`deploy/README.md`](deploy/README.md) | Canary deployment quick start, script reference, env vars, canary phases, rollback procedures |
| [`plans/cicd-pipeline.md`](plans/cicd-pipeline.md) | CI/CD pipeline design document |
| [`plans/kubernetes-deployment-guide.md`](plans/kubernetes-deployment-guide.md) | K8s deployment guide |
| [`plans/canary-runbook.md`](plans/canary-runbook.md) | Canary runbook with rollback triggers |
| [`plans/final-canary-readiness-report.md`](plans/final-canary-readiness-report.md) | Canary readiness assessment |

---

## 2. Overlap Analysis

### 2.1 Critical: Three Competing Sources of Truth for Service Deployments

The same 8 microservices are defined in **three separate places** with subtle differences:

| Aspect | Helm (`helm/miniurl/`) | `k8s/services/*.yaml` | `k8s/miniurl-all-in-one.yaml` |
|---|---|---|---|
| Image registry | `ghcr.io/gallantsuri1/miniurl-api/` | `miniurl/` (local) | `miniurl/` (local) |
| Image tag | `latest` (configurable) | `latest` (hardcoded) | `latest` (hardcoded) |
| Replicas | Configurable per environment | Hardcoded | Hardcoded |
| Resource requests/limits | **Missing** | Present (250m-2000m CPU) | Present (250m-1000m CPU) |
| Probes (liveness/readiness) | **Missing** | Present | Present |
| Namespace | Configurable (`miniurl`) | **Missing** (requires `-n` flag) | Hardcoded (`miniurl`) |
| Port numbers | Inconsistent with k8s/services | Inconsistent with Helm | Inconsistent with both |

**Example: redirect-service port**
- Helm values: `containerPort: 8080`, `servicePort: 8080`
- `k8s/services/redirect-service.yaml`: `containerPort: 8080`, service `port: 8080`
- `k8s/miniurl-all-in-one.yaml`: `containerPort: 8082`, service `port: 8082`
- `docker-compose.yml`: internal `8082`, mapped to host `8083`

### 2.2 Duplicate Monitoring Manifests

Prometheus and Grafana are defined in **two files**:
- [`k8s/infrastructure/global-config.yaml`](k8s/infrastructure/global-config.yaml) (lines 20-188)
- [`k8s/infrastructure/monitoring.yaml`](k8s/infrastructure/monitoring.yaml) (entire file)

These are nearly identical but not guaranteed to stay in sync.

### 2.3 Duplicate ELK Config

Logstash configuration exists in both:
- [`k8s/infrastructure/elk.yaml`](k8s/infrastructure/elk.yaml) (lines 196-231, inline ConfigMap)
- [`k8s/infrastructure/elk-config.yaml`](k8s/infrastructure/elk-config.yaml) (lines 16-49, separate ConfigMap)

### 2.4 Duplicate HPAs

HPAs for 4 services exist in:
- [`k8s/miniurl-all-in-one.yaml`](k8s/miniurl-all-in-one.yaml) (lines 1126-1231)
- [`k8s/hpa/hpa.yaml`](k8s/hpa/hpa.yaml) (entire file)
- Helm [`templates/hpa.yaml`](helm/miniurl/templates/hpa.yaml) (conditional, configurable)

### 2.5 Stale Dockerfile

[`Dockerfile.multi`](Dockerfile.multi) uses JDK 17 and is not referenced by docker-compose.yml or any CI/CD workflow. The active [`Dockerfile`](Dockerfile) uses JDK 21.

---

## 3. Gap Analysis — What Is Missing

### 3.1 Environment Promotion Pipeline

**Current state:** `main-pipeline.yml` deploys directly with `values-prod.yaml` on every push to main. There is no:
- Dev environment deployment
- Staging environment
- Pre-production validation
- Approval gate before production
- Canary deployment automation in CI/CD

**What's needed:** A multi-environment pipeline:
```
PR merge → Build → Deploy DEV → Integration tests → Deploy STAGING → Approval gate → Canary to PROD
```

### 3.2 Helm Chart Completeness

The Helm chart is a good start but missing critical production resources:

| Missing Template | Why Needed |
|---|---|
| `ingress.yaml` | Route external traffic; currently only in raw k8s |
| `secrets.yaml` | Manage secrets declaratively; currently manual via `create-secrets.sh` |
| `serviceaccount.yaml` | RBAC for pods; needed for IRSA/Workload Identity |
| `networkpolicy.yaml` | Pod-to-pod traffic control; exists only in raw k8s |
| `pdb.yaml` | Availability during voluntary disruptions; exists only in raw k8s |
| `resourcequota.yaml` | Namespace resource limits |
| `NOTES.txt` | Post-install instructions for Helm users |

### 3.3 Resource Definitions in Helm

The Helm `deployment.yaml` template does **not** include:
- `resources.requests/limits` — defined in values but not rendered
- `livenessProbe` / `readinessProbe` — defined in values but not rendered
- `volumeMounts` for secrets/configs
- `securityContext`
- `terminationGracePeriodSeconds`

### 3.4 Database Migration Automation

[`run-migrations.sh`](scripts/deploy/run-migrations.sh) is a well-written manual script but:
- Not called from any GitHub Actions workflow
- No versioning/tracking of which migrations have been applied
- No rollback support for migrations
- Uses raw SQL files rather than a migration tool (Flyway/Liquibase)

### 3.5 Secret Management

**Current state is dangerous:**
- [`k8s/miniurl-all-in-one.yaml`](k8s/miniurl-all-in-one.yaml) contains **hardcoded passwords** (`rootpassword123`, `urlpassword123`, etc.)
- [`k8s/infrastructure/global-config.yaml`](k8s/infrastructure/global-config.yaml) has `MYSQL_ROOT_PASSWORD: "root"` in plaintext
- No integration with External Secrets Operator, Sealed Secrets, or cloud secret managers
- RSA keys are generated on-demand by `create-secrets.sh` — no key rotation strategy

### 3.6 Local Minikube Workflow

The README mentions `kubectl apply -f k8s/` but there is no:
- Minikube-specific setup script or profile config
- `skaffold.yaml` or `tilt` configuration for local K8s development
- Script to load local Docker images into Minikube (`minikube image load`)
- Minikube-specific values file for Helm (reduced resource requests)

### 3.7 Rollback Automation

- [`rollback-canary.sh`](scripts/deploy/rollback-canary.sh) exists but is **manual only**
- `main-pipeline.yml` has no rollback job
- No `helm rollback` integration in CI/CD
- No automated rollback trigger based on Prometheus alerts

### 3.8 Smoke Tests / Post-Deployment Validation

The `main-pipeline.yml` verify step only runs `kubectl rollout status`. Missing:
- HTTP smoke tests (hit key endpoints, verify 2xx responses)
- JWKS endpoint validation
- Redirect flow end-to-end test
- Database connectivity check
- Kafka topic/consumer group health check

### 3.9 Image Tag Strategy

Current tagging is inconsistent:

| Workflow | Tags Produced | Problem |
|---|---|---|
| `pr-validation.yml` | `pr-{N}`, `{service}-{version}` | PR number is ephemeral; no way to trace back to commit |
| `main-pipeline.yml` | `latest`, `sha-{8}`, `main`, `{service}-{version}` | `latest` is mutable and dangerous for production |
| `release.yml` | `{version}`, `latest`, `{service}-{version}` | Only workflow producing immutable semver tags |

Helm values all reference `tag: latest` which is mutable and defeats rollback capability.

### 3.10 Infrastructure as Code (Terraform)

A [`terraform/`](terraform/) directory exists but:
- Not referenced in any workflow
- No documentation on what it provisions
- Not integrated with the deployment pipeline

### 3.11 Observability of Deployments

- Slack notifications exist in `main-pipeline.yml` but only for success/failure
- No deployment metrics (DORA metrics: deployment frequency, lead time, MTTR, change failure rate)
- No deployment event tracking in monitoring dashboards

---

## 4. Source of Truth Recommendations

### 4.1 What Should Be the Single Source of Truth

| Domain | Recommended Source of Truth | Rationale |
|---|---|---|
| **Service deployments** | **Helm chart** (`helm/miniurl/`) | Templating, environment-specific values, version control, `helm rollback` support |
| **Infrastructure (Redis, Kafka, MySQL)** | **Helm dependencies** or **Terraform** | Bitnami charts already used in scripts; make them Chart dependencies |
| **Secrets** | **External Secrets Operator** or **Sealed Secrets** + Helm | Never commit secrets to git; `create-secrets.sh` is a bootstrap helper only |
| **Monitoring stack** | **Separate Helm chart** or **kube-prometheus-stack** | Prometheus/Grafana/AlertManager should be managed independently from app |
| **ELK stack** | **Separate Helm chart** or **ECK operator** | Logging infrastructure is independent from the application |
| **Network policies** | **Helm templates** | Should be part of the app chart, environment-aware |
| **PDBs** | **Helm templates** | Should be part of the app chart, replica-count-aware |
| **Ingress** | **Helm template** | Environment-specific hosts/TLS need templating |

### 4.2 What Should Be Deprecated

| Asset | Action | Reason |
|---|---|---|
| [`k8s/miniurl-all-in-one.yaml`](k8s/miniurl-all-in-one.yaml) | **DELETE** | Redundant with Helm + individual k8s files; contains hardcoded secrets; impossible to keep in sync |
| [`k8s/hpa/hpa.yaml`](k8s/hpa/hpa.yaml) | **DELETE** | Redundant with Helm HPA template |
| [`k8s/infrastructure/global-config.yaml`](k8s/infrastructure/global-config.yaml) | **DELETE** (monitoring parts) | Duplicate of `monitoring.yaml`; ConfigMap part superseded by Helm |
| [`k8s/infrastructure/elk-config.yaml`](k8s/infrastructure/elk-config.yaml) | **MERGE into `elk.yaml`** or **DELETE** | Duplicate logstash-config |
| [`Dockerfile.multi`](Dockerfile.multi) | **DELETE** | Stale; JDK 17; not referenced anywhere |

### 4.3 What Should Be Retained as Helper Scripts

| Script | Retention Strategy |
|---|---|
| `scripts/deploy/preflight.sh` | Retain as **local dev helper**; integrate checks into CI/CD as composite action |
| `scripts/deploy/bootstrap-infra.sh` | Retain for **first-time environment bootstrap**; eventually replace with Terraform or Helm dependencies |
| `scripts/deploy/create-secrets.sh` | Retain for **initial secret generation**; document as bootstrap-only; production should use External Secrets |
| `scripts/deploy/run-migrations.sh` | Retain; integrate into CI/CD as a **Job** in the deployment workflow |
| `scripts/deploy/apply-monitoring.sh` | Retain for **local/manual monitoring setup**; production monitoring should be independently deployed |
| `scripts/deploy/capture-baseline.sh` | Retain; integrate into canary workflow as automated step |
| `scripts/deploy/start-canary.sh` | Retain as **reference implementation**; logic should be ported to GitHub Actions workflow |
| `scripts/deploy/rollback-canary.sh` | Retain as **emergency manual tool**; add automated rollback to CI/CD |
| `scripts/init-*.sql` | Retain; integrate with migration tool (Flyway/Liquibase) |
| `scripts/reset-admin-password.sh` | Retain as operational utility |

---

## 5. Critical Risks Identified

| Risk | Severity | Description |
|---|---|---|
| **Hardcoded secrets in git** | 🔴 CRITICAL | [`k8s/miniurl-all-in-one.yaml`](k8s/miniurl-all-in-one.yaml:66:72) contains plaintext passwords committed to version control |
| **Direct-to-prod on merge** | 🔴 CRITICAL | [`main-pipeline.yml`](.github/workflows/main-pipeline.yml:130:148) deploys every main push with `--atomic` and no approval |
| **Mutable `latest` tag in production** | 🔴 CRITICAL | Helm values use `tag: latest`; impossible to know what's running or rollback deterministically |
| **No rollback in CI/CD** | 🟠 HIGH | If `--atomic` fails, Helm rolls back; but no manual/programmatic rollback workflow exists |
| **Three competing sources of truth** | 🟠 HIGH | Helm, k8s/services, and miniurl-all-in-one.yaml all define the same services differently |
| **Self-hosted runner dependency** | 🟡 MEDIUM | Deploy job requires `self-hosted` runner; single point of failure |
| **No migration automation in CI/CD** | 🟡 MEDIUM | DB schema changes must be applied manually before deployment |
| **No smoke tests after deploy** | 🟡 MEDIUM | Rollout status ≠ application health; broken deployments can pass |
| **Stale Dockerfile.multi** | 🟢 LOW | Confusion risk; someone might update the wrong Dockerfile |

---

## 6. Summary of Findings

| Category | Count |
|---|---|
| Total assets audited | 40+ files across 8 directories |
| Duplicate/overlapping definitions | 5 areas (services, monitoring, ELK, HPAs, Dockerfiles) |
| Missing Helm templates | 6 (ingress, secrets, serviceaccount, networkpolicy, pdb, resourcequota) |
| Missing CI/CD stages | 5 (dev deploy, staging deploy, approval gate, canary automation, automated rollback) |
| Security risks | 2 critical (hardcoded secrets, mutable tags) |
| Assets to deprecate | 5 files |
| Assets to retain/enhance | 10 scripts |

---

## 7. Immediate Actions (Before Phase 2)

1. **Rotate all hardcoded secrets** in [`k8s/miniurl-all-in-one.yaml`](k8s/miniurl-all-in-one.yaml:66:72) immediately — they are exposed in git history
2. **Add `.gitignore` rules** for any files containing secrets
3. **Stop using `latest` tag** in Helm values; switch to `sha-{hash}` or semver
4. **Delete** [`Dockerfile.multi`](Dockerfile.multi) to eliminate confusion
5. **Decide:** Will Helm be the source of truth? (Recommended: Yes)

---

## Next: Phase 2 — Target Deployment Architecture

See [`plans/target-deployment-architecture.md`](plans/target-deployment-architecture.md) for the designed end-state covering all 7 deployment flows.
