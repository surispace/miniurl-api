# MiniURL API - Setup and Deployment Guide

## Quick Start

```bash
# 1. Install prerequisites
#    JDK 17+, Maven 3.8+, Docker, kubectl, Helm 3.14+

# 2. Start infrastructure
docker compose up -d

# 3. Build the project
mvn clean install -DskipTests

# 4. Run a service
mvn spring-boot:run -pl identity-service
```

## Deployment Options

| Environment | Guide | Values File | Scripts |
|-------------|-------|-------------|---------|
| **Local K8s (Minikube)** | [Local Minikube Guide](docs/development/local-minikube.md) | `values-local.yaml` | `scripts/local/minikube-*.sh` |
| **Home Server (K3s)** | [Home Server K3s Guide](docs/deployment/home-server-k3s.md) | `values-home.yaml` | CI/CD via self-hosted runner |
| **Development (CI)** | [GitHub Actions Reference](docs/deployment/github-actions.md) | `values-dev.yaml` | `build-and-dev-deploy.yml` |
| **Production** | [Release Process](docs/deployment/release-process.md) | `values-prod.yaml` | `promote-to-release.yml` + `deploy-to-production.yml` |
| **Docker Compose** | [Local Docker Compose](docs/deployment/local-docker-compose.md) | — | `docker compose up -d` |

### Minikube (one-shot)

```bash
./scripts/local/minikube-start.sh
./scripts/local/minikube-build-images.sh
./scripts/local/minikube-deploy.sh
./scripts/local/minikube-smoke-test.sh
```

### K3s Home Server (manual)

```bash
# After K3s is installed and the self-hosted runner is configured:
helm upgrade --install miniurl ./helm/miniurl \
  --values ./helm/miniurl/values-home.yaml \
  --set services.identity-service.image.tag=identity-service-dev-abc12345 \
  --namespace miniurl --create-namespace --wait --atomic
```

## GitHub Environments & Secrets

### Environments

Create these in **Repo → Settings → Environments**:

| Environment | Purpose | Required Reviewers |
|-------------|---------|-------------------|
| `development` | Auto-deploy on merge to main | None |
| `release` | Promote dev images to release | 1 |
| `production` | Deploy release to production | 1 |

### Secrets (per environment)

| Secret | Purpose |
|--------|---------|
| `DB_ROOT_PASSWORD` | MySQL root password |
| `JWT_SECRET` | RS256 signing key (generate with `openssl rand -base64 64`) |
| `SMTP_HOST` | SMTP server hostname |
| `SMTP_PORT` | SMTP server port |
| `SMTP_USERNAME` | SMTP username |
| `SMTP_PASSWORD` | SMTP password |
| `DOCKER_USER` | Docker Hub username (set at repo level) |
| `DOCKER_API_TOKEN` | Docker Hub API token with read/write access (set at repo level) |

**No `KUBECONFIG` secret is needed** when using the self-hosted runner — it reads `~/.kube/config` from the home server directly.

## Self-Hosted Runner (Home Server)

The repository uses a self-hosted runner installed on the home server for deployment. The runner:

- Connects **outbound** to GitHub over HTTPS (WebSocket) — no inbound ports needed
- Reads kubeconfig from `~/.kube/config` — no SSH access or kubeconfig secret
- Runs `build-and-dev-deploy.yml` and `deploy-to-production.yml` workflows with `[self-hosted, home-server]` labels

Setup: follow the [Home Server K3s Guide](docs/deployment/home-server-k3s.md), section 7.

## CI/CD Workflow Guide

### 1. Build and Dev Deploy (Auto)

Triggered automatically when a PR is merged to `main`. Only changed services are built.

```bash
# The workflow runs automatically on push to main
# No manual action needed
```

**What happens:**
1. Detects changed services via `git diff`
2. Builds and pushes dev images to Docker Hub: `{service}-dev-{short_sha}`
3. Updates `image-tags-dev.yaml` and `pending-releases.yaml`
4. Deploys only changed services to dev via Helm with per-service image tags

### 2. Promote to Release (Manual + Approval)

After verifying the dev deployment, promote images to release.

```bash
# Trigger via GitHub UI or CLI:
gh workflow run promote-to-release.yml \
  --field services="identity-service,url-service" \
  --field version="1.0.0"
```

**What happens:**
1. Requires approval from the `release` GitHub Environment
2. Pulls dev images and tags them as release: `{service}-release-{version}`
3. Pushes release tags to Docker Hub
4. Updates `release-tags.yaml` and clears promoted entries from `pending-releases.yaml`

### 3. Deploy to Production (Manual + Approval)

After release promotion, deploy to production.

```bash
# Trigger via GitHub UI or CLI:
gh workflow run deploy-to-production.yml \
  --field version="1.0.0" \
  --field services="identity-service,url-service"
```

**What happens:**
1. Requires approval from the `production` GitHub Environment
2. Loads release tags for the specified version from `release-tags.yaml`
3. Deploys only specified services to production via Helm with per-service image tags
4. Uses Kubernetes RollingUpdate strategy (maxSurge: 1, maxUnavailable: 0)
5. Runs smoke tests and updates `prod-deployments.yaml`

## Build & Test Commands

```bash
# Full build (skip tests)
mvn clean install -DskipTests

# All tests
mvn clean test -DskipITs

# Single module
mvn test -pl url-service -am

```

## Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `APP_BASE_URL` | Application base URL | `http://localhost:8080` |
| `EUREKA_SERVER_URL` | Eureka server URL | `http://localhost:8761/eureka/` |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka bootstrap servers | `localhost:9092` |
| `MYSQL_ROOT_PASSWORD` | MySQL root password | — |

## Reference

- [CLAUDE.md](CLAUDE.md) — Build commands, architecture overview, test patterns
- [CI/CD Pipeline (README)](README.md#cicd-pipeline) — All 5 workflows and their responsibilities
- [Troubleshooting (Home Server K3s)](docs/deployment/home-server-k3s.md#12-troubleshooting) — Runner offline, image pull failures, pod issues
- [Troubleshooting (Minikube)](docs/development/local-minikube.md#6-troubleshooting) — Minikube-specific issues
