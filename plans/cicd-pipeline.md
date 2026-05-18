# MyURL Microservices - CI/CD Pipeline Documentation

## Overview

This document describes the CI/CD pipeline for the MyURL microservices project, including GitHub Actions workflows, Docker image building, and Kubernetes deployment.

---

## 1. GitHub Actions Workflows

### 1.1 PR Validation Workflow

**File:** `.github/workflows/pr-validation.yml`

**Triggers:**
- Pull requests to `main` or `master` branches
- Excludes documentation changes

**Jobs:**
1. **build-and-test**
   - Checkout code
   - Set up JDK 17
   - Cache Maven dependencies
   - Run unit tests
   - Run integration tests (optional)
   - Upload test results

**Configuration:**
```yaml
on:
  pull_request:
    branches:
      - main
      - master
    paths-ignore:
      - 'README.md'
      - 'SETUP_GUIDE.md'
```

### 1.2 Main Pipeline Workflow

**File:** `.github/workflows/main-pipeline.yml`

**Triggers:**
- Push to `main` or `master` branches
- Excludes documentation changes

**Jobs:**
1. **build-and-test**
   - Checkout code
   - Set up JDK 17
   - Cache Maven dependencies
   - Run unit tests
   - Run integration tests

2. **build-and-push-docker**
   - Checkout code
   - Set up JDK 17
   - Set up Docker Buildx
   - Log in to GitHub Container Registry
   - Extract metadata (SHA, latest, branch)
   - Build and push Docker images for all services

3. **deploy-to-kubernetes**
   - Checkout code
   - Set up kubectl
   - Configure Kubernetes cluster
   - Update image tags in K8s manifests
   - Apply Kubernetes manifests
   - Verify deployment
   - Notify success/failure

**Configuration:**
```yaml
on:
  push:
    branches:
      - main
      - master
    paths-ignore:
      - 'README.md'
      - 'SETUP_GUIDE.md'
```

---

## 2. Docker Image Building

### 2.1 Multi-Stage Dockerfile

**File:** `Dockerfile.multi`

**Build Stages:**
1. **build-base** - Common dependencies
2. **build-<service>** - Service-specific build
3. **runtime-<service>** - Runtime image

**Runtime Images:**
- `eclipse-temurin:17-jre` - Lightweight JRE
- Non-root user (`appuser`)
- Health checks
- Resource limits

### 2.2 Image Tagging Strategy

| Tag Type | Format | Purpose |
|----------|--------|---------|
| SHA | `<service>:<git-sha>` | Immutable, used for deployment |
| Latest | `<service>:latest` | Floating tag for latest stable |
| Branch | `<service>:<branch-name>` | Branch-specific builds |

### 2.3 Docker Build Commands

```bash
# Build all services
docker build -f Dockerfile.multi --target build-api-gateway -t miniurl/api-gateway:latest .
docker build -f Dockerfile.multi --target build-eureka-server -t miniurl/eureka-server:latest .
docker build -f Dockerfile.multi --target build-identity-service -t miniurl/identity-service:latest .
docker build -f Dockerfile.multi --target build-url-service -t miniurl/url-service:latest .
docker build -f Dockerfile.multi --target build-redirect-service -t miniurl/redirect-service:latest .
docker build -f Dockerfile.multi --target build-feature-service -t miniurl/feature-service:latest .
docker build -f Dockerfile.multi --target build-notification-service -t miniurl/notification-service:latest .
docker build -f Dockerfile.multi --target build-analytics-service -t miniurl/analytics-service:latest .

# Push to registry
docker push miniurl/api-gateway:latest
docker push miniurl/eureka-server:latest
docker push miniurl/identity-service:latest
docker push miniurl/url-service:latest
docker push miniurl/redirect-service:latest
docker push miniurl/feature-service:latest
docker push miniurl/notification-service:latest
docker push miniurl/analytics-service:latest
```

---

## 3. Kubernetes Deployment

### 3.1 Namespace Structure

**Namespace:** `miniurl`

**Resources:**
- ConfigMaps
- Secrets
- PVCs
- Services
- Deployments
- HPAs
- Ingress

### 3.2 Infrastructure Services

#### MySQL (StatefulSet)
- `mysql-url` - URL service database
- `mysql-identity` - Identity service database
- `mysql-feature` - Feature service database
- `mysql-analytics` - Analytics service database

#### Redis (Deployment)
- `redis` - Distributed caching

#### Kafka (StatefulSet)
- `kafka` - Message broker

### 3.3 Microservices (Deployments)

| Service | Port | Replicas | HPA |
|---------|------|----------|-----|
| api-gateway | 8080 | 2 | Yes (2-10) |
| eureka-server | 8761 | 2 | No |
| identity-service | 8081 | 2 | Yes (2-6) |
| url-service | 8081 | 2 | Yes (2-8) |
| redirect-service | 8082 | 3 | Yes (3-20) |
| feature-service | 8083 | 2 | No |
| notification-service | 8082 | 1 | No |
| analytics-service | 8083 | 1 | No |

### 3.4 Horizontal Pod Autoscaler

**File:** `k8s/hpa/hpa.yaml`

**Metrics:**
- CPU utilization (target: 70%)
- Memory utilization (target: 80%)

**HPA Definitions:**
- `api-gateway-hpa` - 2-10 replicas
- `redirect-service-hpa` - 3-20 replicas
- `url-service-hpa` - 2-8 replicas
- `identity-service-hpa` - 2-6 replicas

### 3.5 Ingress

**File:** `k8s/miniurl-all-in-one.yaml`

**Configuration:**
- Ingress class: nginx
- SSL redirect: enabled
- Rewrite target: `/`

**Routes:**
- `api.miniurl.com/api` → api-gateway:80
- `api.miniurl.com/r` → redirect-service:8082
- `miniurl.com/` → api-gateway:80

---

## 4. Secrets Management

### 4.1 GitHub Secrets

| Secret | Description |
|--------|-------------|
| `DOCKER_USER` | Docker Hub username |
| `DOCKER_API_TOKEN` | Docker Hub API token |
| `KUBE_CONFIG` | Base64 encoded kubeconfig |
| `KUBE_CONTEXT` | Kubernetes context name |
| `SLACK_WEBHOOK_URL` | Slack webhook for notifications |

### 4.2 Kubernetes Secrets

**File:** `k8s/miniurl-all-in-one.yaml`

| Secret | Keys |
|--------|------|
| `db-secrets` | MYSQL_ROOT_PASSWORD, MYSQL_URL_PASSWORD, MYSQL_IDENTITY_PASSWORD, MYSQL_FEATURE_PASSWORD, MYSQL_ANALYTICS_PASSWORD |
| `jwt-secrets` | JWT_SECRET, JWT_EXPIRATION_MS |
| `grafana-secret` | admin-user, admin-password |
| `smtp-secrets` | SMTP_HOST, SMTP_PORT, SMTP_USERNAME, SMTP_PASSWORD |

---

## 5. Deployment Process

### 5.1 Manual Deployment

```bash
# 1. Clone repository
git clone https://github.com/gallantsuri1/miniurl.git
cd miniurl

# 2. Configure kubectl
mkdir -p $HOME/.kube
echo "${KUBE_CONFIG}" | base64 -d > $HOME/.kube/config
kubectl config use-context ${KUBE_CONTEXT}

# 3. Apply infrastructure
kubectl apply -f k8s/infrastructure/

# 4. Apply services
kubectl apply -f k8s/services/

# 5. Apply HPA
kubectl apply -f k8s/hpa/

# 6. Apply ingress
kubectl apply -f k8s/ingress/
```

### 5.2 CI/CD Deployment

```bash
# 1. Push to main branch
git push origin main

# 2. Wait for GitHub Actions workflow to complete
# 3. Verify deployment
kubectl -n miniurl rollout status deployment/api-gateway
kubectl -n miniurl rollout status deployment/eureka-server
kubectl -n miniurl rollout status deployment/identity-service
kubectl -n miniurl rollout status deployment/url-service
kubectl -n miniurl rollout status deployment/redirect-service
kubectl -n miniurl rollout status deployment/feature-service
kubectl -n miniurl rollout status deployment/notification-service
kubectl -n miniurl rollout status deployment/analytics-service
```

---

## 6. Monitoring and Observability

### 6.1 Prometheus

**Endpoints:**
- `http://prometheus.miniurl.svc.cluster.local:9090`

**Scraped Metrics:**
- `/actuator/prometheus` from all services

### 6.2 Grafana

**Endpoints:**
- `http://grafana.miniurl.svc.cluster.local:3000`
- Default credentials: `admin/admin123`

**Dashboards:**
- Spring Boot metrics
- Kubernetes metrics
- Custom dashboards

### 6.3 ELK Stack

**Endpoints:**
- Elasticsearch: `http://elasticsearch.miniurl.svc.cluster.local:9200`
- Kibana: `http://kibana.miniurl.svc.cluster.local:5601`

**Log Collection:**
- Kafka topics: `notifications`, `clicks`
- Index pattern: `miniurl-logs-*`

---

## 7. Troubleshooting

### 7.1 Common Issues

#### Pod CrashLoopBackOff
```bash
# Check logs
kubectl -n miniurl logs <pod-name>

# Check events
kubectl -n miniurl describe pod <pod-name>

# Check resource limits
kubectl -n miniurl top pods
```

#### Service Not Found
```bash
# Check service exists
kubectl -n miniurl get svc

# Check endpoints
kubectl -n miniurl get endpoints <service-name>

# Check DNS
kubectl -n miniurl exec -it <pod-name> -- nslookup <service-name>
```

#### Database Connection Failed
```bash
# Check MySQL pods
kubectl -n miniurl get pods -l app=mysql

# Check PVC
kubectl -n miniurl get pvc

# Check secrets
kubectl -n miniurl get secret db-secrets -o yaml
```

#### Kafka Connection Failed
```bash
# Check Kafka pods
kubectl -n miniurl get pods -l app=kafka

# Check Kafka service
kubectl -n miniurl get svc kafka-service

# Check Kafka logs
kubectl -n miniurl logs <kafka-pod-name>
```

---

## 8. Best Practices

### 8.1 Docker
- Use multi-stage builds
- Use non-root user
- Set resource limits
- Use health checks
- Tag images with SHA

### 8.2 Kubernetes
- Use resource requests/limits
- Configure health probes
- Use rolling updates
- Configure HPA for high-traffic services
- Use ConfigMaps for non-sensitive data
- Use Secrets for sensitive data

### 8.3 CI/CD
- Run tests on PRs
- Use caching for dependencies
- Tag images with SHA
- Verify deployment
- Notify on failure

---

## 9. Rollback Strategy

### 9.1 Manual Rollback

```bash
# List deployments
kubectl -n miniurl rollout history deployment/api-gateway

# Rollback to previous version
kubectl -n miniurl rollout undo deployment/api-gateway

# Rollback to specific revision
kubectl -n miniurl rollout undo deployment/api-gateway --to-revision=1
```

### 9.2 CI/CD Rollback

```yaml
# Add to workflow
- name: Rollback on failure
  if: failure()
  run: |
    kubectl -n miniurl rollout undo deployment/api-gateway
    kubectl -n miniurl rollout undo deployment/eureka-server
    kubectl -n miniurl rollout undo deployment/identity-service
    kubectl -n miniurl rollout undo deployment/url-service
    kubectl -n miniurl rollout undo deployment/redirect-service
    kubectl -n miniurl rollout undo deployment/feature-service
    kubectl -n miniurl rollout undo deployment/notification-service
    kubectl -n miniurl rollout undo deployment/analytics-service