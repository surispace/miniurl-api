# Local Development with Minikube

Run MiniURL in a local Kubernetes cluster using Minikube — ideal for testing Helm charts, canary deployments, HPA behavior, and NetworkPolicies before pushing to shared environments.

## Quick Start

```bash
# Start Minikube with sufficient resources
minikube start --cpus=4 --memory=8192 --disk-size=20g

# Deploy MiniURL with local values
helm upgrade --install miniurl ./helm/miniurl \
  --values ./helm/miniurl/values-local.yaml \
  --namespace miniurl \
  --create-namespace \
  --timeout 10m \
  --wait

# Enable ingress addon
minikube addons enable ingress

# Get access URL
minikube service api-gateway -n miniurl --url
```

## Prerequisites

- Minikube v1.32+
- Helm v3.14+
- kubectl v1.29+
- Docker Desktop (or HyperKit/VirtualBox)

## Building Images for Minikube

Minikube runs its own Docker daemon. Point your Docker client at it:

```bash
eval $(minikube docker-env)

# Build all service images
for svc in eureka-server api-gateway identity-service url-service redirect-service feature-service notification-service analytics-service; do
  docker build --target "$svc" -t "miniurl/${svc}:latest" .
done
```

Or use **Skaffold** for rapid iteration:

```bash
skaffold dev
```

## Testing Canary Deployments

```bash
# Deploy stable
helm upgrade --install miniurl ./helm/miniurl \
  --values ./helm/miniurl/values-local.yaml \
  --namespace miniurl

# Deploy canary at 10%
helm upgrade --install miniurl ./helm/miniurl \
  --values ./helm/miniurl/values-local.yaml \
  --values ./helm/miniurl/values-canary.yaml \
  --set canary.weight=10 \
  --namespace miniurl

# Bump to 25%
helm upgrade --install miniurl ./helm/miniurl \
  --values ./helm/miniurl/values-local.yaml \
  --values ./helm/miniurl/values-canary.yaml \
  --set canary.weight=25 \
  --namespace miniurl

# Promote (disable canary)
helm upgrade --install miniurl ./helm/miniurl \
  --values ./helm/miniurl/values-local.yaml \
  --set canary.enabled=false \
  --namespace miniurl
```

## Testing HPA

```bash
# Enable metrics-server
minikube addons enable metrics-server

# Generate load
kubectl -n miniurl run load-generator --image=busybox -- /bin/sh -c \
  "while true; do wget -q -O- http://api-gateway/api/health; done"

# Watch HPA
kubectl -n miniurl get hpa -w
```

## Testing NetworkPolicies

```bash
# Verify policies are active
kubectl -n miniurl get networkpolicy

# Test from a pod outside the namespace (should be denied)
kubectl run test-pod --image=busybox --rm -it --restart=Never -- \
  wget -q -O- --timeout=5 http://api-gateway.miniurl.svc.cluster.local/api/health
```

## Useful Commands

```bash
# Dashboard
minikube dashboard

# View all MiniURL resources
kubectl -n miniurl get all

# Port-forward a service
kubectl -n miniurl port-forward svc/api-gateway 8080:80

# View logs
kubectl -n miniurl logs -l app=api-gateway --tail=50 -f

# SSH into Minikube
minikube ssh

# Reset everything
minikube delete && minikube start --cpus=4 --memory=8192
```

## Configuration

[`values-local.yaml`](../../helm/miniurl/values-local.yaml) is optimized for Minikube:

- Single replica per service
- HPA disabled
- Lower resource requests (100m CPU, 256Mi memory)
- NetworkPolicies disabled
- ResourceQuota disabled
- PDB disabled
- TLS disabled
- `IfNotPresent` image pull policy

Override any setting:

```bash
helm upgrade --install miniurl ./helm/miniurl \
  --values ./helm/miniurl/values-local.yaml \
  --set services.redirect-service.replicas=3 \
  --namespace miniurl
```

## Comparison: Docker Compose vs Minikube

| Feature | Docker Compose | Minikube |
|---------|---------------|----------|
| Start time | ~30s | ~2min |
| Resource usage | Lower | Higher |
| Helm chart testing | ❌ | ✅ |
| Canary deployments | ❌ | ✅ |
| HPA testing | ❌ | ✅ |
| NetworkPolicy testing | ❌ | ✅ |
| Service discovery (Eureka) | ✅ | ✅ |
| Hot reload (Skaffold) | ❌ | ✅ |
| Production parity | Low | High |
| Best for | Feature dev, debugging | K8s-specific testing |
