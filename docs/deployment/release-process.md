# Release Process

How MiniURL releases flow from a developer's machine to production, with canary deployment and automated rollback capabilities.

## Overview

```
PR Merge → Dev Deploy (auto) → Manual Trigger → Canary 10% → 25% → 50% → 100% (Promote)
                                                    ↓ (any phase fails)
                                               Auto-rollback
```

## Image Tag Strategy

| Environment | Tag Format | Mutability | Set By |
|------------|-----------|------------|--------|
| PR validation | `pr-{N}` | Mutable (overwritten per push) | [`pr-validation.yml`](../../.github/workflows/pr-validation.yml) |
| Development | `sha-{short_hash}` | Immutable | [`deploy-dev.yml`](../../.github/workflows/deploy-dev.yml) |
| Production | `sha-{short_hash}` | Immutable | [`deploy-prod.yml`](../../.github/workflows/deploy-prod.yml) |
| Release | `{semver}` (e.g., `1.2.0`) | Immutable | [`release.yml`](../../.github/workflows/release.yml) |

**`latest` is never used for dev or prod deployments.** The Helm chart's `IMAGE_TAG` global override ensures every pod runs an exact, traceable image.

## Flow Details

### 1. Pull Request → Dev

When a PR merges to `main`:

1. [`deploy-dev.yml`](../../.github/workflows/deploy-dev.yml) triggers automatically
2. Builds all 8 service images tagged `sha-{short_hash}`
3. Pushes to `ghcr.io/surispace/miniurl-api/{service}:sha-{hash}`
4. Deploys to dev cluster via Helm with `--set globalConfig.IMAGE_TAG=sha-{hash}`
5. Runs smoke test against `/api/health`
6. Notifies Slack on success/failure

### 2. Production Canary Deployment

Triggered manually via **Actions → Deploy to Production (Canary) → Run workflow**:

| Phase | Environment Gate | Canary Weight | Stabilization | Action |
|-------|-----------------|---------------|---------------|--------|
| Pre-flight | `production` | — | — | Helm lint, cluster connectivity, capture current revision |
| Canary 10% | `production-canary-10` | 10% | 60s | Deploy canary pods, verify rollout |
| Canary 25% | `production-canary-25` | 25% | 60s | Bump weight, check alerts |
| Canary 50% | `production-canary-50` | 50% | 60s | Bump weight, check alerts |
| Promote | `production` | 100% (canary disabled) | — | Deploy stable, clean up canary resources, smoke test |

Each phase requires a separate **GitHub Environment** approval. Configure required reviewers in **Settings → Environments**.

**Emergency skip**: Check `skip_canary` to deploy directly (bypasses all canary phases). Use only for critical hotfixes.

### 3. Rollback

Triggered via **Actions → Rollback Deployment → Run workflow**:

- Select environment (`dev` or `prod`)
- Optionally specify a revision number (default: previous revision)
- Executes `helm rollback`, verifies all deployments, runs smoke test

Can also be done manually:

```bash
# View history
helm history miniurl -n miniurl

# Rollback to previous
helm rollback miniurl -n miniurl

# Rollback to specific revision
helm rollback miniurl 5 -n miniurl
```

### 4. Semantic Release

Triggered by pushing a `v*` tag (e.g., `v1.2.0`):

1. [`release.yml`](../../.github/workflows/release.yml) builds multi-arch images (amd64 + arm64)
2. Tags images with semver: `ghcr.io/.../api-gateway:1.2.0`
3. Also pushes to Docker Hub: `surispace/miniurl-api:api-gateway-1.2.0`
4. Creates a GitHub Issue summarizing published images

## Canary Architecture

When `canary.enabled=true`:

- A second set of Deployments, Services, and HPAs is created with `-canary` suffix
- A canary Ingress is created with `nginx.ingress.kubernetes.io/canary: "true"` and `canary-weight`
- The stable ingress continues serving the remaining traffic
- Prometheus metrics are labeled with `canary: "true"` for comparison

When promoted (canary disabled):

- Stable deployments are updated to the new image
- Canary resources are deleted
- Canary ingress is removed

## Required GitHub Secrets

| Secret | Used By | Description |
|--------|---------|-------------|
| `DEV_KUBECONFIG` | deploy-dev, rollback, bootstrap | Base64-encoded kubeconfig for dev cluster |
| `PROD_KUBECONFIG` | deploy-prod, rollback, bootstrap | Base64-encoded kubeconfig for prod cluster |
| `DOCKER_USER` | All build jobs | Docker Hub username |
| `DOCKER_API_TOKEN` | All build jobs | Docker Hub access token |
| `DB_ROOT_PASSWORD` | bootstrap | MySQL root password |
| `JWT_SECRET` | bootstrap | JWT signing secret |
| `SMTP_HOST/PORT/USERNAME/PASSWORD` | bootstrap | SMTP credentials |
| `GRAFANA_ADMIN_PASSWORD` | bootstrap | Grafana admin password |
| `SLACK_WEBHOOK_URL` | All deploy jobs | Slack incoming webhook for notifications |

## Required GitHub Environments

Configure in **Settings → Environments**:

| Environment | Required Reviewers | Wait Timer |
|------------|-------------------|------------|
| `development` | None (auto-deploy) | 0 |
| `production` | At least 1 | 0 |
| `production-canary-10` | At least 1 | 0 |
| `production-canary-25` | At least 1 | 0 |
| `production-canary-50` | At least 1 | 0 |
