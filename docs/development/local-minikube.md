# MiniURL — Local Minikube Development Guide

Run the full MiniURL microservices stack locally on Minikube for development
and testing. All 8 services, plus infrastructure (MySQL, Redis, Kafka), run
inside a Minikube VM or Docker container.

## Table of Contents

1. [Prerequisites](#1-prerequisites)
2. [Quick Start](#2-quick-start)
3. [Full Walkthrough](#3-full-walkthrough)
4. [Hybrid Mode (IDE + Minikube Infra)](#4-hybrid-mode-ide--minikube-infra)
5. [Script Reference](#5-script-reference)
6. [Troubleshooting](#6-troubleshooting)

---

## 1. Prerequisites

| Tool | Version | Install |
|------|---------|---------|
| Minikube | latest | `brew install minikube` (macOS) or [minikube start](https://minikube.sigs.k8s.io/docs/start/) |
| Docker | 24+ | Docker Desktop or `brew install docker` |
| Helm | 3.14+ | `brew install helm` |
| kubectl | 1.29+ | Bundled with Docker Desktop or `brew install kubectl` |

Minimum resources for Minikube: 4 CPUs, 8 GB memory, 20 GB disk.

## 2. Quick Start

```bash
# 1. Start Minikube
./scripts/local/minikube-start.sh

# 2. Build all service images inside Minikube
./scripts/local/minikube-build-images.sh

# 3. Deploy infrastructure + app via Helm
./scripts/local/minikube-deploy.sh

# 4. Smoke test
./scripts/local/minikube-smoke-test.sh

# 5. Tear down when done
./scripts/local/minikube-teardown.sh
```

After deploy, access the API:

```bash
# Port-forward API Gateway
kubectl -n miniurl-local port-forward svc/api-gateway 8080:80

# In another terminal
curl http://localhost:8080/actuator/health
curl http://localhost:8080/api/auth/signup \
  -H "Content-Type: application/json" \
  -d '{"username":"demo","email":"demo@test.com","password":"Demo1234!"}'
```

## 3. Full Walkthrough

### 3.1 Start Minikube

```bash
./scripts/local/minikube-start.sh
```

What it does:
- Starts Minikube with profile `miniurl` (4 CPUs, 8 GB RAM, Docker driver)
- Enables `ingress` and `metrics-server` addons
- Idempotent — safe to re-run

Customize resources:
```bash
MINIKUBE_CPUS=6 MINIKUBE_MEMORY=12288 ./scripts/local/minikube-start.sh
```

### 3.2 Build Images

```bash
./scripts/local/minikube-build-images.sh
```

What it does:
- Runs `eval $(minikube docker-env)` to point Docker CLI at Minikube's Docker daemon
- Builds all 8 service images using the multi-stage Dockerfile
- Tags them as `miniurl/<service>:local`
- ImagePullPolicy is set to `Never` in values-local.yaml, so K3s uses the locally-built images

### 3.3 Deploy with Helm

```bash
./scripts/local/minikube-deploy.sh
```

What it does:
- Deploys MiniURL to namespace `miniurl-local` using `values-local.yaml`
- Uses Helm `upgrade --install` (idempotent)
- Shows pods, services, and ingress after deployment

### 3.4 Smoke Test

```bash
./scripts/local/minikube-smoke-test.sh
```

Checks:
1. All pods Running
2. API Gateway `/actuator/health`
3. JWKS endpoint `/.well-known/jwks.json`
4. Redirect endpoint `/r/nonexistent`
5. Auth signup `POST /api/auth/signup`

### 3.5 Tear Down

```bash
# Remove app only, keep cluster
./scripts/local/minikube-teardown.sh

# Destroy everything
DESTROY_CLUSTER=true ./scripts/local/minikube-teardown.sh
```

## 4. Hybrid Mode (IDE + Minikube Infra)

For rapid iteration on a single service, run infrastructure in Minikube
and your service in the IDE:

```bash
# 1. Start Minikube and install infrastructure
./scripts/local/minikube-start.sh

# 2. Install only MySQL, Redis, Kafka, Eureka (skip app services)
helm repo add bitnami https://charts.bitnami.com/bitnami --force-update
helm repo update

# Install a single MySQL for identity-service
helm upgrade --install mysql-identity bitnami/mysql \
  --namespace miniurl-local --create-namespace \
  --set auth.rootPassword=localdev \
  --set auth.database=identity_db \
  --set primary.resources.requests.memory=256Mi

# Port-forward MySQL
kubectl -n miniurl-local port-forward svc/mysql-identity 33061:3306 &

# 3. Run your service in the IDE
# Update application-dev.yml to point to localhost:33061
mvn spring-boot:run -pl identity-service
```

## 5. Script Reference

| Script | Purpose |
|--------|---------|
| `minikube-start.sh` | Start Minikube cluster, enable addons |
| `minikube-build-images.sh` | Build all 8 Docker images in Minikube's Docker daemon |
| `minikube-deploy.sh` | Deploy app via Helm with `values-local.yaml` |
| `minikube-smoke-test.sh` | Validate deployment with health checks |
| `minikube-teardown.sh` | Uninstall app, optionally destroy cluster |

All scripts are idempotent and use `set -euo pipefail`.

### Environment variables

| Variable | Default | Used By |
|----------|---------|---------|
| `MINIKUBE_PROFILE` | `miniurl` | all scripts |
| `MINIKUBE_CPUS` | `4` | minikube-start.sh |
| `MINIKUBE_MEMORY` | `8192` | minikube-start.sh |
| `IMAGE_PREFIX` | `miniurl` | minikube-build-images.sh |
| `IMAGE_TAG` | `local` | minikube-build-images.sh |
| `NAMESPACE` | `miniurl-local` | deploy, smoke-test, teardown |
| `RELEASE_NAME` | `miniurl` | deploy, teardown |
| `DESTROY_CLUSTER` | `false` | teardown |

## 6. Troubleshooting

### `minikube start` fails

```bash
minikube delete --all --purge
minikube start --driver=docker --cpus=4 --memory=8192
```

### Images not found

If pods show `ErrImageNeverPull`:
```bash
# Verify images in Minikube's Docker
eval $(minikube docker-env)
docker images miniurl/*:local

# Rebuild if missing
./scripts/local/minikube-build-images.sh
```

### Port-forward conflict

```bash
# Find and kill existing port-forwards
lsof -i :8080
kill <PID>
```

### Out of memory

Reduce Minikube resource usage:
```bash
# Shut down non-essential services by scaling to 0
kubectl -n miniurl-local scale deployment notification-service --replicas=0
kubectl -n miniurl-local scale deployment analytics-service --replicas=0
kubectl -n miniurl-local scale deployment feature-service --replicas=0
```

Or use lower resource requests in a custom values override file:

```yaml
# values-local-lowres.yaml
services:
  api-gateway:
    resources:
      requests: { cpu: "50m", memory: "128Mi" }
      limits: { cpu: "200m", memory: "256Mi" }
```

```bash
helm upgrade --install miniurl ./helm/miniurl \
  -f helm/miniurl/values-local.yaml \
  -f values-local-lowres.yaml \
  -n miniurl-local
```

### Cluster disk full

```bash
minikube ssh -- docker system prune -af
```
