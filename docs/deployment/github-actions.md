# GitHub Actions CI/CD Reference

Complete reference for all 6 GitHub Actions workflows that power the MiniURL CI/CD pipeline.

## Workflow Map

```
┌──────────────────────────────────────────────────────────────┐
│                        PR opened/updated                      │
│                              │                                │
│                    ┌─────────▼──────────┐                     │
│                    │  pr-validation.yml │                     │
│                    │  Build, Test,      │                     │
│                    │  Helm Lint,        │                     │
│                    │  Docker (pr-N tag) │                     │
│                    └────────────────────┘                     │
│                              │                                │
│                         PR merged                             │
│                              │                                │
│                    ┌─────────▼──────────┐                     │
│                    │   deploy-dev.yml   │                     │
│                    │  Build sha-{hash}, │                     │
│                    │  Deploy to Dev     │                     │
│                    └────────────────────┘                     │
│                              │                                │
│                    Manual trigger (workflow_dispatch)         │
│                              │                                │
│                    ┌─────────▼──────────┐                     │
│                    │  deploy-prod.yml   │                     │
│                    │  Canary: 10→25→50  │                     │
│                    │  → Promote 100%    │                     │
│                    └────────────────────┘                     │
│                              │                                │
│                    ┌─────────▼──────────┐                     │
│                    │   rollback.yml     │  ← Anytime          │
│                    │  helm rollback     │                     │
│                    └────────────────────┘                     │
│                                                               │
│   ┌──────────────────────┐    ┌──────────────────────────┐   │
│   │  bootstrap-          │    │  release.yml              │   │
│   │  environment.yml     │    │  Multi-arch build on v*   │   │
│   │  New env from scratch│    │  tag, push semver images  │   │
│   └──────────────────────┘    └──────────────────────────┘   │
└──────────────────────────────────────────────────────────────┘
```

## Workflow Details

### [`pr-validation.yml`](../../.github/workflows/pr-validation.yml)

**Trigger**: Pull request to `main`/`master`

**Jobs**:

| Job | Purpose | Artifacts |
|-----|---------|-----------|
| `build-and-test` | Maven `clean test -DskipITs` | Test reports (7-day retention) |
| `helm-lint` | `helm lint` + `helm template` for local/dev/prod/canary + IMAGE_TAG override | — |
| `build-and-push-docker` | Build per-service Docker images tagged `pr-{N}` | Pushed to GHCR |

**Concurrency**: Cancel in-progress on new push to same PR.

### [`deploy-dev.yml`](../../.github/workflows/deploy-dev.yml)

**Trigger**: Push to `main`/`master` (auto) or `workflow_dispatch` (manual)

**Jobs**:

| Job | Purpose |
|-----|---------|
| `build-and-push` | Build all 8 services, tag `sha-{short_hash}`, push to GHCR |
| `deploy` | `helm upgrade --install` with `values-dev.yaml`, verify rollouts, smoke test |

**Environment**: `development` (no approval required)

### [`deploy-prod.yml`](../../.github/workflows/deploy-prod.yml)

**Trigger**: `workflow_dispatch` only (manual)

**Inputs**:
- `image_tag` (required): SHA-tagged image to deploy
- `skip_canary` (boolean): Emergency direct deploy

**Jobs**:

| Job | Environment Gate | Purpose |
|-----|-----------------|---------|
| `preflight` | `production` | Helm lint, template validation, cluster connectivity, capture current revision |
| `canary-10` | `production-canary-10` | Deploy canary at 10% weight, 60s stabilization |
| `canary-25` | `production-canary-25` | Bump to 25%, 60s stabilization |
| `canary-50` | `production-canary-50` | Bump to 50%, 60s stabilization |
| `promote` | `production` | Disable canary, deploy stable, clean up canary resources, smoke test |
| `deploy-direct` | `production` | Direct deploy (skip canary) |
| `notify-failure` | — | Slack notification on any phase failure |

### [`rollback.yml`](../../.github/workflows/rollback.yml)

**Trigger**: `workflow_dispatch` only

**Inputs**:
- `environment`: `dev` or `prod`
- `revision`: Revision number (0 = previous)

**Jobs**: Single job — show history, execute `helm rollback`, verify deployments, smoke test.

### [`bootstrap-environment.yml`](../../.github/workflows/bootstrap-environment.yml)

**Trigger**: `workflow_dispatch` only

**Inputs**:
- `environment`: `dev` or `prod`
- `install_infra`: Install MySQL, Kafka, Redis
- `install_monitoring`: Install Prometheus + Grafana

**Jobs**: Single job — create namespace, create secrets, optionally install infra/monitoring, install MiniURL via Helm.

### [`release.yml`](../../.github/workflows/release.yml)

**Trigger**: Push of `v*` tag or `workflow_dispatch`

**Jobs**: Single job — multi-arch Docker build (amd64 + arm64), push semver tags to GHCR and Docker Hub, create GitHub Issue.

## Concurrency Strategy

| Workflow | Strategy |
|----------|----------|
| `pr-validation` | Cancel in-progress (same PR) |
| `deploy-dev` | Queue (no cancel — don't interrupt deploys) |
| `deploy-prod` | N/A (manual only) |
| `rollback` | N/A (manual only) |
| `bootstrap-environment` | N/A (manual only) |
| `release` | N/A (tag/manual) |

## Adding a New Service

1. Add the service directory under repo root
2. Add a `Dockerfile` target in the multi-stage build
3. Add the service to the matrix in:
   - `pr-validation.yml` → `build-and-push-docker.strategy.matrix.service`
   - `deploy-dev.yml` → `build-and-push.strategy.matrix.service`
4. Add service configuration to `helm/miniurl/values.yaml` under `services`
5. Add NetworkPolicy in `helm/miniurl/templates/networkpolicy.yaml`
6. Add to verification loops in all deploy/rollback workflows
