# Initial Environment Bootstrap

How to provision a new MiniURL environment from scratch — namespace, secrets, infrastructure, and the MiniURL application itself.

## Prerequisites

- `kubectl` configured with cluster-admin access to the target cluster
- `helm` v3.14+ installed
- Required secrets available in your password manager:
  - MySQL root password
  - JWT signing secret
  - SMTP credentials (host, port, username, password)
  - Grafana admin password (if installing monitoring)

## Quick Start (GitHub Actions)

The recommended approach is [`bootstrap-environment.yml`](../../.github/workflows/bootstrap-environment.yml):

1. Go to **Actions → Bootstrap Environment → Run workflow**
2. Select environment: `dev` or `prod`
3. Choose options:
   - `install_infra`: Check if this is a brand-new cluster (installs MySQL, Kafka, Redis)
   - `install_monitoring`: Check to install Prometheus + Grafana
4. Click **Run workflow**

This provisions everything in one shot using secrets stored in GitHub Environments.

## Manual Bootstrap

Use this when GitHub Actions cannot reach the cluster or for air-gapped environments.

### 1. Create Namespace

```bash
kubectl create namespace miniurl
```

### 2. Create Secrets

```bash
# Database credentials
kubectl -n miniurl create secret generic db-secrets \
  --from-literal=MYSQL_ROOT_PASSWORD='<your-root-password>'

# JWT signing keys
kubectl -n miniurl create secret generic jwt-rsa-keys \
  --from-literal=jwt-secret='<your-jwt-secret>'

# SMTP credentials (for notification-service)
kubectl -n miniurl create secret generic smtp-credentials \
  --from-literal=SMTP_HOST='smtp.example.com' \
  --from-literal=SMTP_PORT='587' \
  --from-literal=SMTP_USERNAME='noreply@example.com' \
  --from-literal=SMTP_PASSWORD='<smtp-password>'
```

### 3. Install Infrastructure (first time only)

Add the Bitnami Helm repo:

```bash
helm repo add bitnami https://charts.bitnami.com/bitnami
helm repo update
```

Install MySQL instances (one per bounded context):

```bash
for db in url identity feature analytics; do
  helm upgrade --install "mysql-${db}" bitnami/mysql \
    --namespace miniurl \
    --set auth.rootPassword='<root-password>' \
    --set auth.database="${db}_db" \
    --set primary.persistence.size=10Gi \
    --wait --timeout 10m
done
```

Install Redis:

```bash
helm upgrade --install redis bitnami/redis \
  --namespace miniurl \
  --set architecture=standalone \
  --set auth.enabled=false \
  --wait --timeout 5m
```

Install Kafka:

```bash
helm upgrade --install kafka bitnami/kafka \
  --namespace miniurl \
  --set replicaCount=1 \
  --set persistence.size=10Gi \
  --wait --timeout 10m
```

### 4. Install Monitoring (optional)

```bash
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update

helm upgrade --install prometheus prometheus-community/kube-prometheus-stack \
  --namespace monitoring \
  --create-namespace \
  --set prometheus.prometheusSpec.serviceMonitorSelectorNilUsesHelmValues=false \
  --set grafana.adminPassword='<admin-password>' \
  --wait --timeout 10m
```

### 5. Install MiniURL

```bash
# Development
helm upgrade --install miniurl ./helm/miniurl \
  --values ./helm/miniurl/values-dev.yaml \
  --namespace miniurl \
  --create-namespace \
  --timeout 10m \
  --wait

# Production
helm upgrade --install miniurl ./helm/miniurl \
  --values ./helm/miniurl/values-prod.yaml \
  --namespace miniurl \
  --create-namespace \
  --timeout 10m \
  --wait
```

### 6. Verify

```bash
kubectl -n miniurl get pods,svc,ingress
kubectl -n miniurl get deployment -o wide
```

All 8 services should show `READY` with the expected replica count.

## Post-Bootstrap

- **DNS**: Point `api.miniurl.com` (prod) or `dev.api.miniurl.com` (dev) to the ingress controller's external IP
- **TLS**: cert-manager will automatically provision certificates if `clusterIssuer` is configured in values
- **Migrations**: Run database migrations via the `run-migrations.sh` script or the migration job
- **Monitoring**: Import the Grafana dashboards from `deploy/monitoring/dashboards/`

## Troubleshooting

| Symptom | Likely Cause | Fix |
|---------|-------------|-----|
| `ImagePullBackOff` | GHCR auth missing | Create image pull secret for `ghcr.io` |
| `CrashLoopBackOff` | DB connection refused | Verify MySQL services are running and secrets are correct |
| `Pending` pods | Insufficient cluster resources | Reduce replica counts or increase node pool |
| Ingress not resolving | DNS not configured | Point DNS A record to ingress controller IP |
