# MiniURL Target Deployment Architecture

**Date:** 2026-04-30
**Status:** Design — Phase 2
**Depends on:** [`plans/deployment-gap-analysis.md`](deployment-gap-analysis.md)

---

## Table of Contents

1. [Design Principles](#1-design-principles)
2. [Environment Model](#2-environment-model)
3. [Flow 1: Developer Local Run](#3-flow-1-developer-local-run)
4. [Flow 2: Local Minikube Deploy](#4-flow-2-local-minikube-deploy)
5. [Flow 3: Dev Environment Deployment (PR Merge)](#5-flow-3-dev-environment-deployment-pr-merge)
6. [Flow 4: Prod Deployment with Approval](#6-flow-4-prod-deployment-with-approval)
7. [Flow 5: First-Time Environment Bootstrap](#7-flow-5-first-time-environment-bootstrap)
8. [Flow 6: Normal Release Upgrades](#8-flow-6-normal-release-upgrades)
9. [Flow 7: Rollback](#9-flow-7-rollback)
10. [Helm Chart Target State](#10-helm-chart-target-state)
11. [GitHub Actions Workflow Architecture](#11-github-actions-workflow-architecture)
12. [Image Tag Strategy](#12-image-tag-strategy)
13. [Secret Management](#13-secret-management)
14. [Migration Strategy](#14-migration-strategy)
15. [Observability](#15-observability)
16. [Migration Path from Current State](#16-migration-path-from-current-state)

---

## 1. Design Principles

| Principle | Implementation |
|---|---|
| **Helm is the single source of truth** | All Kubernetes resources (Deployments, Services, Ingress, HPA, PDB, NetworkPolicy, ServiceAccount, ConfigMap) are rendered from the Helm chart. Raw k8s manifests are deprecated. |
| **Immutable image tags only** | Production never uses `latest`. Every deployment references a `sha-{git-hash}` or semver tag. |
| **Environment promotion** | Images flow: PR → DEV → STAGING → PROD. The same image artifact is promoted, never rebuilt. |
| **GitOps-ready** | Helm values files are the declarative desired state. The pipeline `helm upgrade --install` applies them. Ready for ArgoCD/Flux adoption later. |
| **Secrets never touch git** | All secrets are managed externally (Sealed Secrets for gitops, or External Secrets Operator for cloud). Bootstrap scripts generate initial secrets only. |
| **Idempotent operations** | Every script and workflow step can be re-run safely. |
| **Local dev parity** | Docker Compose mirrors the K8s service topology. Minikube uses the same Helm chart with a local values file. |

---

## 2. Environment Model

```
┌────────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐
│   LOCAL    │    │   DEV    │    │ STAGING  │    │   PROD   │
│ (Compose/  │    │ (K8s)    │    │  (K8s)   │    │  (K8s)   │
│  Minikube) │    │          │    │          │    │          │
└────────────┘    └──────────┘    └──────────┘    └──────────┘
     │                  │               │               │
     │ values-local     │ values-dev    │ values-stag   │ values-prod
     │ (or compose)     │               │               │
     ▼                  ▼               ▼               ▼
┌─────────────────────────────────────────────────────────────┐
│                    Helm Chart (helm/miniurl/)                │
│  templates/: deployment, service, ingress, hpa, pdb,        │
│             networkpolicy, configmap, serviceaccount,       │
│             secrets (placeholder), NOTES.txt                │
└─────────────────────────────────────────────────────────────┘
```

| Environment | Purpose | Infrastructure | Image Source | Trigger |
|---|---|---|---|---|
| **local** | Developer workstation | Docker Compose or Minikube | Local build (`mvn spring-boot:run` or local Docker) | Manual |
| **dev** | Integration testing, PR validation | K8s cluster (shared) | ghcr.io `sha-{hash}` | Auto on PR merge |
| **staging** | Pre-production validation, canary dry-run | K8s cluster (shared or dedicated) | ghcr.io `sha-{hash}` (same as dev) | Auto after dev passes |
| **prod** | Live traffic | K8s cluster (dedicated) | ghcr.io `sha-{hash}` (promoted from staging) | Manual approval + canary |

---

## 3. Flow 1: Developer Local Run

### 3.1 Docker Compose (Infrastructure + All Services)

```bash
# Start all infrastructure + services
docker compose up -d

# View logs
docker compose logs -f

# Stop everything
docker compose down -v
```

**What runs:**
- Zookeeper + Kafka (port 9092)
- Redis (port 6379)
- MySQL × 3: identity (33061), url (33062), feature (33063)
- Eureka Server (port 8761)
- All 8 microservices built from the multi-stage Dockerfile
- Prometheus (port 9090) + Grafana (port 3000)

### 3.2 Hybrid Mode (Compose Infra + IDE Services)

For rapid iteration on a single service:

```bash
# Start only infrastructure
docker compose up -d zookeeper kafka redis mysql-identity mysql-url mysql-feature eureka-server

# Run your service in the IDE or via Maven
mvn spring-boot:run -pl identity-service
```

**Service `application-dev.yml` must point to localhost-mapped ports:**
```yaml
# identity-service/src/main/resources/application-dev.yml
spring:
  datasource:
    url: jdbc:mysql://localhost:33061/identity_db?useSSL=false&allowPublicKeyRetrieval=true
    username: root
    password: root
eureka:
  client:
    service-url:
      defaultZone: http://localhost:8761/eureka/
kafka:
  bootstrap-servers: localhost:9092
```

### 3.3 Docker Compose File Structure (Target)

The current [`docker-compose.yml`](docker-compose.yml) is already well-structured. Minor improvements:

- Add `profiles` to separate infrastructure-only from full-stack
- Add healthcheck dependencies so services wait for their DBs
- Document the hybrid mode in comments

---

## 4. Flow 2: Local Minikube Deploy

### 4.1 Prerequisites

```bash
minikube start --cpus=4 --memory=8192 --driver=docker
minikube addons enable ingress
minikube addons enable metrics-server
```

### 4.2 Deploy Using Helm (Same Chart, Local Values)

```bash
# Build Docker images inside Minikube's Docker daemon
eval $(minikube docker-env)
docker build --target identity-service -t miniurl/identity-service:local .
docker build --target url-service -t miniurl/url-service:local .
# ... repeat for all services

# Deploy with local values
helm upgrade --install miniurl ./helm/miniurl \
  --values ./helm/miniurl/values-local.yaml \
  --namespace miniurl \
  --create-namespace \
  --wait \
  --timeout 5m

# Verify
kubectl get pods -n miniurl
minikube service api-gateway -n miniurl
```

### 4.3 New File: `helm/miniurl/values-local.yaml`

```yaml
# values-local.yaml — Minikube local development
globalConfig:
  SPRING_PROFILES_ACTIVE: "dev"
  APP_BASE_URL: "http://miniurl.local"
  APP_UI_BASE_URL: "http://miniurl.local"
  EUREKA_SERVER_URL: "http://eureka-server:8761/eureka/"
  KAFKA_BOOTSTRAP_SERVERS: "kafka:9092"
  REDIS_HOST: "redis"
  REDIS_PORT: "6379"

services:
  api-gateway:
    replicas: 1
    image:
      repository: miniurl/api-gateway
      tag: local
      pullPolicy: IfNotPresent
    hpa:
      enabled: false
    resources:
      requests: { cpu: "100m", memory: "256Mi" }
      limits: { cpu: "500m", memory: "512Mi" }

  eureka-server:
    replicas: 1
    image:
      repository: miniurl/eureka-server
      tag: local
      pullPolicy: IfNotPresent
    hpa:
      enabled: false
    resources:
      requests: { cpu: "100m", memory: "256Mi" }
      limits: { cpu: "500m", memory: "512Mi" }

  identity-service:
    replicas: 1
    image:
      repository: miniurl/identity-service
      tag: local
      pullPolicy: IfNotPresent
    hpa:
      enabled: false
    resources:
      requests: { cpu: "100m", memory: "256Mi" }
      limits: { cpu: "500m", memory: "512Mi" }

  url-service:
    replicas: 1
    image:
      repository: miniurl/url-service
      tag: local
      pullPolicy: IfNotPresent
    hpa:
      enabled: false
    resources:
      requests: { cpu: "100m", memory: "256Mi" }
      limits: { cpu: "500m", memory: "512Mi" }

  redirect-service:
    replicas: 1
    image:
      repository: miniurl/redirect-service
      tag: local
      pullPolicy: IfNotPresent
    hpa:
      enabled: false
    resources:
      requests: { cpu: "100m", memory: "256Mi" }
      limits: { cpu: "500m", memory: "512Mi" }

  feature-service:
    replicas: 1
    image:
      repository: miniurl/feature-service
      tag: local
      pullPolicy: IfNotPresent
    hpa:
      enabled: false
    resources:
      requests: { cpu: "100m", memory: "256Mi" }
      limits: { cpu: "500m", memory: "512Mi" }

  notification-service:
    replicas: 1
    image:
      repository: miniurl/notification-service
      tag: local
      pullPolicy: IfNotPresent
    hpa:
      enabled: false
    resources:
      requests: { cpu: "100m", memory: "256Mi" }
      limits: { cpu: "500m", memory: "512Mi" }

  analytics-service:
    replicas: 1
    image:
      repository: miniurl/analytics-service
      tag: local
      pullPolicy: IfNotPresent
    hpa:
      enabled: false
    resources:
      requests: { cpu: "100m", memory: "256Mi" }
      limits: { cpu: "500m", memory: "512Mi" }
```

### 4.4 Optional: Skaffold for Rapid Iteration

For developers who want continuous rebuild-on-save in Minikube:

```yaml
# skaffold.yaml (new file at repo root)
apiVersion: skaffold/v4
kind: Config
metadata:
  name: miniurl
build:
  artifacts:
    - image: miniurl/eureka-server
      context: .
      docker:
        target: eureka-server
    - image: miniurl/api-gateway
      context: .
      docker:
        target: api-gateway
    - image: miniurl/identity-service
      context: .
      docker:
        target: identity-service
    - image: miniurl/url-service
      context: .
      docker:
        target: url-service
    - image: miniurl/redirect-service
      context: .
      docker:
        target: redirect-service
    - image: miniurl/feature-service
      context: .
      docker:
        target: feature-service
    - image: miniurl/notification-service
      context: .
      docker:
        target: notification-service
    - image: miniurl/analytics-service
      context: .
      docker:
        target: analytics-service
  local:
    useDockerCLI: true
deploy:
  helm:
    releases:
      - name: miniurl
        chartPath: helm/miniurl
        valuesFiles:
          - helm/miniurl/values-local.yaml
        namespace: miniurl
        createNamespace: true
```

Usage: `skaffold dev` — watches for changes, rebuilds, redeploys.

---

## 5. Flow 3: Dev Environment Deployment (PR Merge)

### 5.1 Trigger

PR merged to `main` → [`pr-validation.yml`](.github/workflows/pr-validation.yml) passes → new workflow triggers.

### 5.2 Pipeline Steps

```
┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐
│  Build   │───▶│  Unit    │───▶│  Build   │───▶│  Deploy  │───▶│  Smoke   │
│  (Maven) │    │  Tests   │    │  Docker  │    │  to DEV  │    │  Tests   │
└──────────┘    └──────────┘    └──────────┘    └──────────┘    └──────────┘
                                                       │               │
                                                       ▼               ▼
                                                ┌──────────┐    ┌──────────┐
                                                │  Image   │    │ Notify   │
                                                │  Tag:    │    │ Slack    │
                                                │ sha-{8}  │    │ (opt)    │
                                                └──────────┘    └──────────┘
```

### 5.3 New Workflow: `.github/workflows/deploy-dev.yml`

```yaml
name: Deploy to Dev

on:
  push:
    branches: [main, master]
    paths-ignore:
      - 'README.md'
      - 'docs/**'
      - 'plans/**'

concurrency:
  group: deploy-dev
  cancel-in-progress: false

env:
  REGISTRY: ghcr.io
  IMAGE_NAMESPACE: ${{ github.repository }}

permissions:
  contents: read
  packages: write
  id-token: write

jobs:
  build-and-push:
    name: Build & Push Images
    runs-on: ubuntu-latest
    outputs:
      image-tag: ${{ steps.meta.outputs.tag }}
    strategy:
      fail-fast: false
      matrix:
        service:
          - eureka-server
          - api-gateway
          - identity-service
          - url-service
          - redirect-service
          - feature-service
          - notification-service
          - analytics-service
    steps:
      - uses: actions/checkout@v4
      - uses: docker/setup-buildx-action@v3
      - uses: docker/login-action@v3
        with:
          registry: ${{ env.REGISTRY }}
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - name: Generate image tag
        id: meta
        run: |
          TAG="sha-${GITHUB_SHA::8}"
          echo "tag=${TAG}" >> $GITHUB_OUTPUT
          echo "full=${REGISTRY}/${IMAGE_NAMESPACE}/${{ matrix.service }}:${TAG}" >> $GITHUB_OUTPUT

      - uses: docker/build-push-action@v5
        with:
          context: .
          target: ${{ matrix.service }}
          push: true
          tags: ${{ steps.meta.outputs.full }}
          cache-from: type=gha
          cache-to: type=gha,mode=max

  deploy-dev:
    name: Deploy to Dev
    runs-on: ubuntu-latest
    needs: build-and-push
    environment:
      name: dev
    steps:
      - uses: actions/checkout@v4

      - name: Configure kubectl
        uses: azure/setup-kubectl@v4
        # Or use your cloud provider's auth action

      - name: Deploy with Helm
        run: |
          helm upgrade --install miniurl-dev ./helm/miniurl \
            --values ./helm/miniurl/values-dev.yaml \
            --set globalConfig.APP_BASE_URL="https://dev.miniurl.example.com" \
            --namespace miniurl-dev \
            --create-namespace \
            --wait \
            --timeout 10m

      - name: Run smoke tests
        run: |
          ./scripts/deploy/smoke-test.sh dev

      - name: Notify Slack
        if: always()
        uses: slackapi/slack-github-action@v1
        with:
          webhook: ${{ secrets.SLACK_WEBHOOK_URL }}
          payload: |
            {
              "text": "Dev deployment ${{ job.status }}",
              "blocks": [{
                "type": "section",
                "text": {
                  "type": "mrkdwn",
                  "text": "*MiniURL Dev Deployment:* ${{ job.status }}\n*Commit:* `${{ github.sha }}`\n*Env:* Dev"
                }
              }]
            }
```

### 5.4 Smoke Test Script: `scripts/deploy/smoke-test.sh`

```bash
#!/usr/bin/env bash
# Smoke tests for post-deployment validation
set -euo pipefail

ENV="${1:-dev}"
BASE_URL="${BASE_URL:-http://localhost:8080}"

echo "=== MiniURL Smoke Tests — ${ENV} ==="

# 1. Health endpoints
echo "--- Health Checks ---"
for svc in api-gateway:8080 identity-service:8081 url-service:8081 redirect-service:8080; do
  name="${svc%%:*}"
  port="${svc##*:}"
  status=$(curl -s -o /dev/null -w "%{http_code}" "${BASE_URL}/actuator/health" || echo "000")
  echo "  ${name}: ${status}"
done

# 2. JWKS endpoint
echo "--- JWKS ---"
jwks_status=$(curl -s -o /dev/null -w "%{http_code}" "${BASE_URL}/.well-known/jwks.json" || echo "000")
echo "  JWKS: ${jwks_status}"

# 3. Public endpoints
echo "--- Public Endpoints ---"
signup_status=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${BASE_URL}/api/auth/signup" \
  -H "Content-Type: application/json" \
  -d '{"username":"smoketest","email":"test@test.com","password":"Test1234!"}' || echo "000")
echo "  Signup: ${signup_status}"

# 4. Redirect endpoint (expect 404 for nonexistent code)
redirect_status=$(curl -s -o /dev/null -w "%{http_code}" "${BASE_URL}/r/nonexistent" || echo "000")
echo "  Redirect (404 expected): ${redirect_status}"

echo "=== Smoke Tests Complete ==="
```

---

## 6. Flow 4: Prod Deployment with Approval

### 6.1 Pipeline Architecture

```
┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐
│  Dev     │───▶│  Deploy  │───▶│  Smoke   │───▶│  Manual  │
│  Passes  │    │  Staging │    │  Tests   │    │  Approval│
└──────────┘    └──────────┘    └──────────┘    └────┬─────┘
                                                     │
              ┌──────────────────────────────────────┘
              ▼
┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐
│  Deploy  │───▶│  Canary  │───▶│  Monitor │───▶│  Full    │
│  Canary  │    │  Phase 1 │    │  5 min   │    │  Promote │
│  (10%)   │    │          │    │          │    │  (100%)  │
└──────────┘    └──────────┘    └──────────┘    └──────────┘
                     │                                ▲
                     │ (if alerts fire)               │
                     ▼                                │
              ┌──────────┐                            │
              │  Auto    │                            │
              │  Rollback│                            │
              └──────────┘                            │
                                                      
  Phase 2 (25%) and Phase 3 (50%) follow the same pattern
  with 10-min and 15-min monitoring windows respectively.
```

### 6.2 New Workflow: `.github/workflows/deploy-prod.yml`

```yaml
name: Deploy to Production

on:
  workflow_dispatch:
    inputs:
      image-tag:
        description: 'Image tag to deploy (sha-{hash})'
        required: true
        type: string
      canary-phase:
        description: 'Canary phase (1-4)'
        required: false
        default: '1'
        type: choice
        options: ['1', '2', '3', '4']

env:
  REGISTRY: ghcr.io
  IMAGE_NAMESPACE: ${{ github.repository }}
  NAMESPACE: miniurl-prod

permissions:
  contents: read
  packages: read
  id-token: write

jobs:
  deploy-canary:
    name: Canary Deployment Phase ${{ inputs.canary-phase }}
    runs-on: ubuntu-latest
    environment:
      name: production
    steps:
      - uses: actions/checkout@v4

      - name: Configure kubectl
        uses: azure/setup-kubectl@v4

      - name: Deploy canary phase ${{ inputs.canary-phase }}
        run: |
          IMAGE_TAG="${{ inputs.image-tag }}"
          
          # For phase 1: deploy canary alongside stable
          if [ "${{ inputs.canary-phase }}" = "1" ]; then
            for svc in eureka-server api-gateway identity-service url-service redirect-service feature-service notification-service analytics-service; do
              kubectl set image deployment/${svc} ${svc}=${REGISTRY}/${IMAGE_NAMESPACE}/${svc}:${IMAGE_TAG} -n ${NAMESPACE}
              kubectl rollout status deployment/${svc} -n ${NAMESPACE} --timeout=5m
            done
          fi
          
          # For phases 2-3: scale up canary replicas
          if [ "${{ inputs.canary-phase }}" = "2" ] || [ "${{ inputs.canary-phase }}" = "3" ]; then
            for svc in eureka-server api-gateway identity-service url-service redirect-service feature-service notification-service analytics-service; do
              CURRENT=$(kubectl get deployment ${svc} -n ${NAMESPACE} -o jsonpath='{.spec.replicas}')
              NEW=$((CURRENT + 1))
              kubectl scale deployment ${svc} --replicas=${NEW} -n ${NAMESPACE}
            done
          fi
          
          # For phase 4: full promotion
          if [ "${{ inputs.canary-phase }}" = "4" ]; then
            helm upgrade --install miniurl ./helm/miniurl \
              --values ./helm/miniurl/values-prod.yaml \
              --set-string globalConfig.IMAGE_TAG=${IMAGE_TAG} \
              --namespace ${NAMESPACE} \
              --wait \
              --timeout 10m
          fi

      - name: Run smoke tests
        run: |
          ./scripts/deploy/smoke-test.sh prod

      - name: Capture baseline metrics
        if: inputs.canary-phase == '1'
        run: |
          ./scripts/deploy/capture-baseline.sh

      - name: Check Prometheus alerts
        run: |
          # Query Prometheus for any firing canary alerts
          ALERTS=$(curl -s "http://prometheus.monitoring.svc.cluster.local:9090/api/v1/alerts" | \
            jq '[.data.alerts[] | select(.labels.canary == "true" and .state == "firing")] | length')
          if [ "$ALERTS" -gt 0 ]; then
            echo "::error::${ALERTS} canary alerts are firing! Investigate before proceeding."
            exit 1
          fi
          echo "No canary alerts firing."

      - name: Notify phase completion
        run: |
          PHASE="${{ inputs.canary-phase }}"
          TRAFFIC=""
          case $PHASE in
            1) TRAFFIC="10%" ;;
            2) TRAFFIC="25%" ;;
            3) TRAFFIC="50%" ;;
            4) TRAFFIC="100% (FULL PROMOTION)" ;;
          esac
          echo "Canary Phase ${PHASE} complete — ${TRAFFIC} traffic"

  rollback:
    name: Emergency Rollback
    runs-on: ubuntu-latest
    if: failure()
    needs: deploy-canary
    environment:
      name: production
    steps:
      - uses: actions/checkout@v4
      - name: Rollback to stable
        run: |
          ./scripts/deploy/rollback-canary.sh
```

### 6.3 Approval Gates

| Gate | Mechanism | Who |
|---|---|---|
| Deploy to staging | Automatic after dev passes | CI/CD |
| Deploy to production | GitHub Environments — required reviewers | Senior engineer / Tech lead |
| Canary phase 1→2 | Manual `workflow_dispatch` with `canary-phase: 2` | On-call engineer |
| Canary phase 2→3 | Manual `workflow_dispatch` with `canary-phase: 3` | On-call engineer |
| Canary phase 3→4 | Manual `workflow_dispatch` with `canary-phase: 4` | On-call engineer |

---

## 7. Flow 5: First-Time Environment Bootstrap

### 7.1 Bootstrap Order

```
1. Create K8s namespace
2. Deploy infrastructure (Redis, Kafka, MySQL)
3. Create secrets (RSA keys, DB passwords, SMTP)
4. Run database migrations
5. Deploy application via Helm
6. Deploy monitoring
7. Verify
```

### 7.2 Bootstrap Script: `scripts/deploy/bootstrap-env.sh`

```bash
#!/usr/bin/env bash
# Full environment bootstrap for MiniURL
# Usage: ENV=dev ./scripts/deploy/bootstrap-env.sh
set -euo pipefail

ENV="${ENV:-dev}"
NAMESPACE="miniurl-${ENV}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "=== Bootstrapping MiniURL Environment: ${ENV} ==="

# 1. Preflight
"${SCRIPT_DIR}/preflight.sh"

# 2. Create namespace
kubectl create namespace "${NAMESPACE}" --dry-run=client -o yaml | kubectl apply -f -

# 3. Deploy infrastructure
"${SCRIPT_DIR}/bootstrap-infra.sh"

# 4. Create secrets
"${SCRIPT_DIR}/create-secrets.sh"

# 5. Run migrations
"${SCRIPT_DIR}/run-migrations.sh"

# 6. Deploy application
VALUES_FILE="./helm/miniurl/values-${ENV}.yaml"
helm upgrade --install "miniurl-${ENV}" ./helm/miniurl \
  --values "${VALUES_FILE}" \
  --namespace "${NAMESPACE}" \
  --create-namespace \
  --wait \
  --timeout 10m

# 7. Apply monitoring
"${SCRIPT_DIR}/apply-monitoring.sh"

# 8. Verify
"${SCRIPT_DIR}/smoke-test.sh" "${ENV}"

echo "=== Environment ${ENV} bootstrapped successfully ==="
```

### 7.3 What's Automated vs Manual

| Step | Automated | Manual |
|---|---|---|
| Namespace creation | ✅ | |
| Redis + Kafka (Helm) | ✅ | |
| MySQL instances | | ❌ (use cloud DB or Helm; bootstrap-infra.sh could be extended) |
| RSA key generation | ✅ | |
| Secret creation | ✅ | |
| DB migrations | ✅ | |
| App deployment (Helm) | ✅ | |
| Monitoring config | ✅ | |
| DNS / TLS certs | | ❌ (environment-specific, use cert-manager) |
| Ingress controller | | ❌ (one-time cluster setup) |

---

## 8. Flow 6: Normal Release Upgrades

### 8.1 Standard Release Path

```
Developer cuts tag v1.2.0
        │
        ▼
┌──────────────────┐
│ release.yml      │  Builds multi-arch images, pushes to ghcr.io
│ (tag push)       │  Tags: 1.2.0, latest
└──────┬───────────┘
       │
       ▼
┌──────────────────┐
│ deploy-dev.yml   │  Deploys sha-{hash} to dev (automatic on merge)
│ (PR merge)       │
└──────┬───────────┘
       │
       ▼
┌──────────────────┐
│ deploy-staging.yml│ Deploys same sha-{hash} to staging
│ (dev success)    │
└──────┬───────────┘
       │
       ▼
┌──────────────────┐
│ deploy-prod.yml  │  Manual approval → canary phases → full promotion
│ (manual trigger) │
└──────────────────┘
```

### 8.2 Helm Upgrade Command (Standard)

```bash
helm upgrade --install miniurl ./helm/miniurl \
  --values ./helm/miniurl/values-prod.yaml \
  --set-string globalConfig.IMAGE_TAG="sha-${GITHUB_SHA::8}" \
  --namespace miniurl-prod \
  --wait \
  --timeout 10m \
  --atomic \
  --cleanup-on-fail
```

`--atomic` ensures: if deployment fails, Helm rolls back to the previous release automatically.

### 8.3 Values File Updates

When configuration changes (new env vars, resource adjustments):
1. Update the appropriate `values-{env}.yaml`
2. Commit to git
3. The next deployment picks up the changes automatically

---

## 9. Flow 7: Rollback

### 9.1 Automatic Rollback (Helm `--atomic`)

If `helm upgrade --install --atomic` fails:
- Helm automatically rolls back to the previous successful release
- No manual intervention needed
- Slack notification sent with failure details

### 9.2 Manual Rollback via Helm

```bash
# List release history
helm history miniurl -n miniurl-prod

# Rollback to previous revision
helm rollback miniurl -n miniurl-prod

# Rollback to specific revision
helm rollback miniurl 3 -n miniurl-prod
```

### 9.3 Emergency Rollback Script

[`scripts/deploy/rollback-canary.sh`](scripts/deploy/rollback-canary.sh) is retained for:
- Rolling back during an active canary (between phases)
- Environments where Helm history is unavailable
- Manual operator intervention

### 9.4 Automated Rollback Trigger (Future Enhancement)

When Prometheus alerts fire during canary:
1. AlertManager sends webhook to GitHub Actions
2. `repository_dispatch` event triggers rollback workflow
3. `helm rollback` executes automatically

This requires AlertManager → GitHub webhook integration (documented but not implemented in Phase 2).

---

## 10. Helm Chart Target State

### 10.1 Complete Template Inventory

```
helm/miniurl/
├── Chart.yaml
├── Chart.lock                    # NEW: dependency lock file
├── values.yaml                   # Base defaults
├── values-local.yaml             # NEW: Minikube local
├── values-dev.yaml               # Dev overrides
├── values-staging.yaml           # NEW: Staging overrides
├── values-prod.yaml              # Prod overrides
├── templates/
│   ├── _helpers.tpl              # Labels, name helpers
│   ├── NOTES.txt                 # NEW: post-install instructions
│   ├── configmap.yaml            # global-config
│   ├── deployment.yaml           # Per-service Deployment (ENHANCED)
│   ├── service.yaml              # Per-service Service
│   ├── ingress.yaml              # NEW: Environment-aware Ingress
│   ├── hpa.yaml                  # Conditional HPA
│   ├── pdb.yaml                  # NEW: PodDisruptionBudget per service
│   ├── networkpolicy.yaml        # NEW: NetworkPolicy per service
│   ├── serviceaccount.yaml       # NEW: ServiceAccount
│   └── secrets.yaml              # NEW: Placeholder secrets (SealedSecrets)
└── charts/                       # NEW: Helm dependencies
    ├── redis-{version}.tgz
    └── kafka-{version}.tgz
```

### 10.2 Enhanced Deployment Template

The current [`templates/deployment.yaml`](helm/miniurl/templates/deployment.yaml) must be enhanced to include:

```yaml
# templates/deployment.yaml (target state)
{{- range $name, $svc := .Values.services }}
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ $name }}
  namespace: {{ $.Values.namespace }}
  labels:
    {{- include "miniurl.labels" (dict "name" $name) | nindent 4 }}
spec:
  replicas: {{ $svc.replicas }}
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1
      maxUnavailable: 0
  selector:
    matchLabels:
      app: {{ $name }}
  template:
    metadata:
      labels:
        app: {{ $name }}
        {{- include "miniurl.labels" (dict "name" $name) | nindent 8 }}
    spec:
      serviceAccountName: {{ $.Values.serviceAccountName | default "miniurl-sa" }}
      terminationGracePeriodSeconds: {{ $svc.terminationGracePeriodSeconds | default 30 }}
      securityContext:
        runAsNonRoot: true
        runAsUser: 1000
      containers:
      - name: {{ $name }}
        image: "{{ $svc.image.repository }}:{{ $svc.image.tag }}"
        imagePullPolicy: {{ $svc.image.pullPolicy | default "IfNotPresent" }}
        ports:
        {{- range $svc.ports }}
        - name: {{ .name }}
          containerPort: {{ .containerPort }}
        {{- end }}
        envFrom:
        - configMapRef:
            name: global-config
        {{- with $svc.env }}
        env:
          {{- toYaml . | nindent 8 }}
        {{- end }}
        {{- with $svc.resources }}
        resources:
          {{- toYaml . | nindent 10 }}
        {{- end }}
        {{- with $svc.probes }}
        livenessProbe:
          {{- toYaml .liveness | nindent 10 }}
        readinessProbe:
          {{- toYaml .readiness | nindent 10 }}
        {{- end }}
        securityContext:
          allowPrivilegeEscalation: false
          readOnlyRootFilesystem: true
          capabilities:
            drop: ["ALL"]
{{- end }}
```

### 10.3 New Ingress Template

```yaml
# templates/ingress.yaml (new)
{{- if .Values.ingress.enabled }}
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: {{ .Release.Name }}-ingress
  namespace: {{ .Values.namespace }}
  annotations:
    cert-manager.io/cluster-issuer: {{ .Values.ingress.clusterIssuer | default "letsencrypt-prod" }}
    nginx.ingress.kubernetes.io/ssl-redirect: "true"
    {{- with .Values.ingress.annotations }}
    {{- toYaml . | nindent 4 }}
    {{- end }}
spec:
  ingressClassName: {{ .Values.ingress.className | default "nginx" }}
  {{- if .Values.ingress.tls }}
  tls:
    - hosts:
        - {{ .Values.ingress.host }}
      secretName: {{ .Release.Name }}-tls
  {{- end }}
  rules:
    - host: {{ .Values.ingress.host }}
      http:
        paths:
          - path: /api
            pathType: Prefix
            backend:
              service:
                name: api-gateway
                port:
                  number: 80
          - path: /r
            pathType: Prefix
            backend:
              service:
                name: redirect-service
                port:
                  number: 8080
          - path: /.well-known
            pathType: Prefix
            backend:
              service:
                name: identity-service
                port:
                  number: 8081
{{- end }}
```

---

## 11. GitHub Actions Workflow Architecture

### 11.1 Target Workflow Inventory

| Workflow File | Trigger | Purpose | Environment |
|---|---|---|---|
| [`pr-validation.yml`](.github/workflows/pr-validation.yml) | PR → main | Build, test, push `pr-{N}` images | N/A |
| [`deploy-dev.yml`](.github/workflows/deploy-dev.yml) | Push to main | Build, push `sha-{hash}`, deploy to dev, smoke test | dev |
| [`deploy-staging.yml`](.github/workflows/deploy-staging.yml) | Workflow call from deploy-dev | Deploy same `sha-{hash}` to staging, smoke test | staging |
| [`deploy-prod.yml`](.github/workflows/deploy-prod.yml) | Manual dispatch | Canary phases 1-4, approval gates, auto-rollback on alert | production |
| [`release.yml`](.github/workflows/release.yml) | Tag `v*` | Multi-arch build, push semver tags, create release issue | N/A |
| [`rollback.yml`](.github/workflows/rollback.yml) | Manual dispatch or repository_dispatch | `helm rollback` to specified revision | production |

### 11.2 Reusable Workflows (DRY)

Extract common steps into reusable workflows:

```yaml
# .github/workflows/_build-and-push.yml (reusable)
name: Build and Push Docker Images

on:
  workflow_call:
    inputs:
      tag-prefix:
        required: true
        type: string
    outputs:
      image-tag:
        value: ${{ jobs.build.outputs.tag }}

jobs:
  build:
    runs-on: ubuntu-latest
    outputs:
      tag: ${{ steps.meta.outputs.tag }}
    strategy:
      fail-fast: false
      matrix:
        service: [eureka-server, api-gateway, identity-service, url-service, redirect-service, feature-service, notification-service, analytics-service]
    steps:
      - uses: actions/checkout@v4
      - uses: docker/setup-buildx-action@v3
      - uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}
      - name: Generate tag
        id: meta
        run: |
          TAG="${{ inputs.tag-prefix }}-${GITHUB_SHA::8}"
          echo "tag=${TAG}" >> $GITHUB_OUTPUT
      - uses: docker/build-push-action@v5
        with:
          context: .
          target: ${{ matrix.service }}
          push: true
          tags: ghcr.io/${{ github.repository }}/${{ matrix.service }}:${{ steps.meta.outputs.tag }}
          cache-from: type=gha
          cache-to: type=gha,mode=max
```

### 11.3 Environment Protection Rules

Configure in GitHub repo Settings → Environments:

| Environment | Required Reviewers | Wait Timer | Deployment Branches |
|---|---|---|---|
| `dev` | None | 0 min | main, master |
| `staging` | None | 0 min | main, master |
| `production` | 2 reviewers | 0 min | main, master |

---

## 12. Image Tag Strategy

### 12.1 Tag Taxonomy

| Tag Pattern | Produced By | Immutable? | Used In |
|---|---|---|---|
| `pr-{N}` | `pr-validation.yml` | Yes (per PR) | PR review, manual testing |
| `sha-{hash}` | `deploy-dev.yml` | Yes | dev, staging, production deployments |
| `v{MAJOR}.{MINOR}.{PATCH}` | `release.yml` | Yes | Release tracking, changelog |
| `latest` | `release.yml` | **No** (mutable) | Documentation only; never in production configs |

### 12.2 Production Image Reference

Helm values for production must use immutable tags:

```yaml
# values-prod.yaml (target)
services:
  api-gateway:
    image:
      repository: ghcr.io/gallantsuri1/miniurl-api/api-gateway
      tag: ""  # Set at deploy time via --set-string
      pullPolicy: IfNotPresent
```

Deploy command:
```bash
helm upgrade --install miniurl ./helm/miniurl \
  --values ./helm/miniurl/values-prod.yaml \
  --set-string services.api-gateway.image.tag="sha-abc12345" \
  --set-string services.identity-service.image.tag="sha-abc12345" \
  # ... all services
```

Or better, use a global image tag:
```yaml
# values-prod.yaml
globalConfig:
  IMAGE_TAG: ""  # Set at deploy time
```

And in templates:
```yaml
image: "{{ $svc.image.repository }}:{{ $.Values.globalConfig.IMAGE_TAG | default $svc.image.tag }}"
```

---

## 13. Secret Management

### 13.1 Target: Sealed Secrets

For a GitOps-friendly approach without external dependencies:

```
Developer/CI/CD                  Git Repository              Kubernetes Cluster
───────────────                  ─────────────               ─────────────────
1. Create plain Secret
   (local only)
        │
2. kubeseal ───────────────▶  sealed-secret.yaml  ──────▶  Sealed Secrets
   (encrypts with                                     Controller decrypts
    cluster pub key)                                  and creates Secret
```

### 13.2 Bootstrap vs Runtime

| Phase | Tool | Purpose |
|---|---|---|
| **First-time bootstrap** | [`create-secrets.sh`](scripts/deploy/create-secrets.sh) | Generate RSA keys, create initial secrets directly in K8s |
| **Ongoing management** | Sealed Secrets + Helm | Encrypted secrets committed to git, decrypted by controller |
| **Production** | External Secrets Operator (future) | Sync secrets from AWS Secrets Manager / GCP Secret Manager |

### 13.3 Secrets Inventory

| Secret Name | Keys | Created By | Rotation |
|---|---|---|---|
| `jwt-rsa-keys` | `private.pem`, `public.pem`, `key-id` | `create-secrets.sh` | Manual (re-generate and update) |
| `db-secrets` | `MYSQL_ROOT_PASSWORD` | `create-secrets.sh` | Per security policy |
| `smtp-credentials` | `username`, `password` | `create-secrets.sh` | When SMTP creds change |
| `grafana-secret` | `admin-user`, `admin-password` | Monitoring bootstrap | Per security policy |

---

## 14. Migration Strategy

### 14.1 Current State → Target State

**Phase A: Adopt Flyway or Liquibase**

Replace raw SQL files with a migration tool:

```
identity-service/src/main/resources/db/migration/
├── V1__create_users_table.sql
├── V2__create_roles_table.sql
├── V3__create_user_roles_table.sql
└── V4__add_2fa_columns.sql
```

Migrations run automatically on service startup (`spring.flyway.enabled=true`).

### 14.2 Migration in CI/CD

For production, run migrations as a Kubernetes Job before deploying new pods:

```yaml
# templates/migration-job.yaml (new)
apiVersion: batch/v1
kind: Job
metadata:
  name: {{ .Release.Name }}-db-migration
  annotations:
    "helm.sh/hook": pre-upgrade
    "helm.sh/hook-weight": "-5"
    "helm.sh/hook-delete-policy": before-hook-creation
spec:
  template:
    spec:
      restartPolicy: Never
      containers:
      - name: migration
        image: "{{ .Values.services.identity-service.image.repository }}:{{ .Values.globalConfig.IMAGE_TAG }}"
        command: ["java", "-jar", "/app/identity-service.jar", "--spring.flyway.enabled=true"]
        envFrom:
        - configMapRef:
            name: global-config
```

---

## 15. Observability

### 15.1 Deployment Events

Every deployment should emit:
- **GitHub Deployment** event (via `actions/github-script`)
- **Slack notification** with: environment, version, commit link, workflow link
- **Prometheus metric**: `miniurl_deployment_info{env, version, sha}` as a gauge

### 15.2 DORA Metrics (Future)

| Metric | How to Measure |
|---|---|
| **Deployment Frequency** | Count of `deploy-prod.yml` successful runs per week |
| **Lead Time for Changes** | Time from commit to production deploy |
| **MTTR** | Time from alert firing to `helm rollback` completion |
| **Change Failure Rate** | % of deployments that triggered rollback |

### 15.3 Dashboard

Add a "Deployments" dashboard in Grafana showing:
- Deployment timeline (when each env was last deployed)
- Currently running version per environment
- Rollback events
- Canary phase progression

---

## 16. Migration Path from Current State

### 16.1 Phase 2A: Immediate Cleanup (Week 1)

| Action | Priority |
|---|---|
| Delete [`k8s/miniurl-all-in-one.yaml`](k8s/miniurl-all-in-one.yaml) | 🔴 CRITICAL |
| Delete [`Dockerfile.multi`](Dockerfile.multi) | 🟠 HIGH |
| Delete [`k8s/hpa/hpa.yaml`](k8s/hpa/hpa.yaml) | 🟠 HIGH |
| Merge [`k8s/infrastructure/elk-config.yaml`](k8s/infrastructure/elk-config.yaml) into [`elk.yaml`](k8s/infrastructure/elk.yaml) | 🟡 MEDIUM |
| Remove duplicate monitoring from [`global-config.yaml`](k8s/infrastructure/global-config.yaml) | 🟡 MEDIUM |
| Rotate all hardcoded secrets | 🔴 CRITICAL |

### 16.2 Phase 2B: Helm Enhancement (Week 1-2)

| Action |
|---|
| Add `templates/ingress.yaml` |
| Add `templates/pdb.yaml` |
| Add `templates/networkpolicy.yaml` |
| Add `templates/serviceaccount.yaml` |
| Add `templates/NOTES.txt` |
| Enhance `templates/deployment.yaml` with resources, probes, securityContext |
| Create `values-local.yaml` for Minikube |
| Create `values-staging.yaml` |
| Add `Chart.lock` with Bitnami Redis + Kafka dependencies |

### 16.3 Phase 2C: CI/CD Restructuring (Week 2-3)

| Action |
|---|
| Split `main-pipeline.yml` into `deploy-dev.yml` + `deploy-staging.yml` + `deploy-prod.yml` |
| Create reusable `_build-and-push.yml` workflow |
| Add smoke test job to all deploy workflows |
| Add `deploy-prod.yml` with canary phases and approval gates |
| Add `rollback.yml` workflow |
| Configure GitHub Environments with protection rules |
| Switch to immutable `sha-{hash}` tags for all deployments |

### 16.4 Phase 2D: Developer Experience (Week 3)

| Action |
|---|
| Create `skaffold.yaml` for Minikube rapid iteration |
| Document hybrid Compose + IDE workflow in README |
| Add `scripts/deploy/smoke-test.sh` |
| Add `scripts/deploy/bootstrap-env.sh` |
| Update [`README.md`](README.md) with all 7 flows |

### 16.5 Phase 2E: Production Hardening (Week 4+)

| Action |
|---|
| Implement Sealed Secrets for secret management |
| Add Flyway/Liquibase migrations |
| Add migration Job as Helm pre-upgrade hook |
| Integrate AlertManager → GitHub webhook for auto-rollback |
| Add DORA metrics dashboard |
| Add cert-manager integration for TLS |

---

## Appendix A: Quick Reference — All 7 Flows

| Flow | Command / Trigger | Environment |
|---|---|---|
| **1. Local Run** | `docker compose up -d` or `mvn spring-boot:run -pl <svc>` | localhost |
| **2. Minikube** | `helm upgrade --install miniurl ./helm/miniurl -f values-local.yaml` | Minikube |
| **3. Dev Deploy** | Auto on push to main | dev K8s |
| **4. Prod Deploy** | Manual `workflow_dispatch` with approval | prod K8s |
| **5. Bootstrap** | `ENV=dev ./scripts/deploy/bootstrap-env.sh` | Any new env |
| **6. Upgrade** | `helm upgrade --install --atomic` via CI/CD | Any existing env |
| **7. Rollback** | `helm rollback` or `./scripts/deploy/rollback-canary.sh` | Any env |

## Appendix B: File Changes Summary

| File | Action |
|---|---|
| [`k8s/miniurl-all-in-one.yaml`](k8s/miniurl-all-in-one.yaml) | DELETE |
| [`k8s/hpa/hpa.yaml`](k8s/hpa/hpa.yaml) | DELETE |
| [`Dockerfile.multi`](Dockerfile.multi) | DELETE |
| [`k8s/infrastructure/elk-config.yaml`](k8s/infrastructure/elk-config.yaml) | DELETE (merge into elk.yaml) |
| [`k8s/infrastructure/global-config.yaml`](k8s/infrastructure/global-config.yaml) | MODIFY (remove duplicate monitoring) |
| [`helm/miniurl/templates/deployment.yaml`](helm/miniurl/templates/deployment.yaml) | ENHANCE (resources, probes, securityContext) |
| [`helm/miniurl/values.yaml`](helm/miniurl/values.yaml) | MODIFY (add resources, probes, ingress config) |
| [`helm/miniurl/values-dev.yaml`](helm/miniurl/values-dev.yaml) | MODIFY (add resources, probes) |
| [`helm/miniurl/values-prod.yaml`](helm/miniurl/values-prod.yaml) | MODIFY (add resources, probes, ingress) |
| [`helm/miniurl/templates/ingress.yaml`](helm/miniurl/templates/ingress.yaml) | CREATE |
| [`helm/miniurl/templates/pdb.yaml`](helm/miniurl/templates/pdb.yaml) | CREATE |
| [`helm/miniurl/templates/networkpolicy.yaml`](helm/miniurl/templates/networkpolicy.yaml) | CREATE |
| [`helm/miniurl/templates/serviceaccount.yaml`](helm/miniurl/templates/serviceaccount.yaml) | CREATE |
| [`helm/miniurl/templates/NOTES.txt`](helm/miniurl/templates/NOTES.txt) | CREATE |
| [`helm/miniurl/values-local.yaml`](helm/miniurl/values-local.yaml) | CREATE |
| [`helm/miniurl/values-staging.yaml`](helm/miniurl/values-staging.yaml) | CREATE |
| [`.github/workflows/deploy-dev.yml`](.github/workflows/deploy-dev.yml) | CREATE |
| [`.github/workflows/deploy-staging.yml`](.github/workflows/deploy-staging.yml) | CREATE |
| [`.github/workflows/deploy-prod.yml`](.github/workflows/deploy-prod.yml) | CREATE |
| [`.github/workflows/rollback.yml`](.github/workflows/rollback.yml) | CREATE |
| [`.github/workflows/_build-and-push.yml`](.github/workflows/_build-and-push.yml) | CREATE |
| [`scripts/deploy/smoke-test.sh`](scripts/deploy/smoke-test.sh) | CREATE |
| [`scripts/deploy/bootstrap-env.sh`](scripts/deploy/bootstrap-env.sh) | CREATE |
| [`skaffold.yaml`](skaffold.yaml) | CREATE |
| [`README.md`](README.md) | UPDATE (document all 7 flows) |
