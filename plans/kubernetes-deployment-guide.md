# MyURL Microservices - Kubernetes Deployment Guide

## Overview

This guide provides step-by-step instructions for deploying the MyURL microservices to a Kubernetes cluster.

---

## 1. Prerequisites

### 1.1 Tools
- **kubectl** (v1.28.0+)
- **Docker** (for building images)
- **Helm** (optional, for advanced deployments)

### 1.2 Kubernetes Cluster
- **Minikube** (for local development)
- **EKS** (for AWS)
- **GKE** (for Google Cloud)
- **AKS** (for Azure)
- **DigitalOcean Kubernetes** (for DO)

### 1.3 Ingress Controller
- **Nginx Ingress Controller** (required)

```bash
# Install Nginx Ingress Controller (Minikube)
minikube addons enable ingress

# Install Nginx Ingress Controller (Generic)
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.8.1/deploy/static/provider/cloud/deploy.yaml
```

---

## 2. Namespace Setup

### 2.1 Create Namespace

```bash
kubectl apply -f k8s/infrastructure/namespace.yaml
```

Or use the all-in-one manifest:

```bash
kubectl apply -f k8s/miniurl-all-in-one.yaml
```

### 2.2 Verify Namespace

```bash
kubectl get namespace miniurl
kubectl get all -n miniurl
```

---

## 3. Infrastructure Setup

### 3.1 Apply Infrastructure

```bash
# Apply infrastructure (MySQL, Redis, Kafka)
kubectl apply -f k8s/infrastructure/

# Wait for pods to be ready
kubectl -n miniurl wait --for=condition=ready pod --all --timeout=300s
```

### 3.2 Verify Infrastructure

```bash
# Check MySQL pods
kubectl -n miniurl get pods -l app=mysql

# Check Redis pod
kubectl -n miniurl get pods -l app=redis

# Check Kafka pod
kubectl -n miniurl get pods -l app=kafka

# Check service endpoints
kubectl -n miniurl get endpoints
```

---

## 4. Service Deployment

### 4.1 Apply Services

```bash
# Apply all services
kubectl apply -f k8s/services/

# Wait for deployments to be ready
kubectl -n miniurl rollout status deployment/api-gateway --timeout=120s
kubectl -n miniurl rollout status deployment/eureka-server --timeout=120s
kubectl -n miniurl rollout status deployment/identity-service --timeout=120s
kubectl -n miniurl rollout status deployment/url-service --timeout=120s
kubectl -n miniurl rollout status deployment/redirect-service --timeout=120s
kubectl -n miniurl rollout status deployment/feature-service --timeout=120s
kubectl -n miniurl rollout status deployment/notification-service --timeout=120s
kubectl -n miniurl rollout status deployment/analytics-service --timeout=120s
```

### 4.2 Verify Services

```bash
# Check all pods
kubectl -n miniurl get pods

# Check all services
kubectl -n miniurl get svc

# Check all deployments
kubectl -n miniurl get deployments
```

---

## 5. Horizontal Pod Autoscaler

### 5.1 Apply HPA

```bash
kubectl apply -f k8s/hpa/
```

### 5.2 Verify HPA

```bash
kubectl -n miniurl get hpa
```

---

## 6. Ingress Configuration

### 6.1 Apply Ingress

```bash
kubectl apply -f k8s/ingress/
```

### 6.2 Verify Ingress

```bash
kubectl -n miniurl get ingress
```

### 6.3 Update DNS

Add to `/etc/hosts` (or your DNS provider):

```
127.0.0.1 api.miniurl.com
127.0.0.1 miniurl.com
```

For production, update your DNS provider with the ingress IP:

```bash
# Get ingress IP
kubectl -n miniurl get svc ingress-nginx-controller
```

---

## 7. Monitoring Setup

### 7.1 Apply Monitoring

```bash
kubectl apply -f k8s/infrastructure/monitoring.yaml
```

### 7.2 Access Prometheus

```bash
# Port forward
kubectl -n miniurl port-forward svc/prometheus 9090:9090

# Access in browser
open http://localhost:9090
```

### 7.3 Access Grafana

```bash
# Port forward
kubectl -n miniurl port-forward svc/grafana 3000:3000

# Access in browser
open http://localhost:3000

# Default credentials
Username: admin
Password: admin123
```

---

## 8. ELK Stack Setup

### 8.1 Apply ELK

```bash
kubectl apply -f k8s/infrastructure/elk.yaml
```

### 8.2 Access Kibana

```bash
# Port forward
kubectl -n miniurl port-forward svc/kibana 5601:5601

# Access in browser
open http://localhost:5601
```

---

## 9. Database Initialization

### 9.1 Initialize MySQL Databases

```bash
# Initialize URL database
kubectl -n miniurl exec -it mysql-url-0 -- mysql -u root -prootpassword123 < scripts/init-url-db.sql

# Initialize Identity database
kubectl -n miniurl exec -it mysql-identity-0 -- mysql -u root -prootpassword123 < scripts/init-identity-db.sql

# Initialize Feature database
kubectl -n miniurl exec -it mysql-feature-0 -- mysql -u root -prootpassword123 < scripts/init-feature-db.sql

# Initialize Analytics database
kubectl -n miniurl exec -it mysql-analytics-0 -- mysql -u root -prootpassword123 < scripts/init-analytics-db.sql
```

---

## 10. Verification

### 10.1 Health Check

```bash
# Check API Gateway health
curl http://api.miniurl.com/api/health

# Check Eureka Server
curl http://eureka-server.miniurl.svc.cluster.local:8761/actuator/health
```

### 10.2 Service Discovery

```bash
# Check Eureka dashboard
kubectl -n miniurl port-forward svc/eureka-server 8761:8761
open http://localhost:8761
```

### 10.3 API Testing

```bash
# Test URL shortening
curl -X POST http://api.miniurl.com/api/urls \
  -H "Content-Type: application/json" \
  -d '{"url": "https://example.com", "alias": "test"}'

# Test redirect
curl -I http://api.miniurl.com/r/test
```

---

## 11. Troubleshooting

### 11.1 Pod Issues

```bash
# Check pod status
kubectl -n miniurl get pods

# Check pod logs
kubectl -n miniurl logs <pod-name>

# Check pod events
kubectl -n miniurl describe pod <pod-name>
```

### 11.2 Service Issues

```bash
# Check service endpoints
kubectl -n miniurl get endpoints <service-name>

# Check service details
kubectl -n miniurl describe svc <service-name>
```

### 11.3 Database Issues

```bash
# Check MySQL logs
kubectl -n miniurl logs <mysql-pod-name>

# Check PVC status
kubectl -n miniurl get pvc

# Check MySQL connection
kubectl -n miniurl exec -it <mysql-pod-name> -- mysql -u root -prootpassword123
```

### 11.4 Kafka Issues

```bash
# Check Kafka logs
kubectl -n miniurl logs <kafka-pod-name>

# Check Kafka topics
kubectl -n miniurl exec -it <kafka-pod-name> -- kafka-topics --list --bootstrap-server localhost:9092
```

### 11.5 Ingress Issues

```bash
# Check ingress controller logs
kubectl -n ingress-nginx logs <ingress-pod-name>

# Check ingress details
kubectl -n miniurl describe ingress miniurl-ingress
```

---

## 12. Scaling

### 12.1 Manual Scaling

```bash
# Scale API Gateway
kubectl -n miniurl scale deployment/api-gateway --replicas=5

# Scale Redirect Service
kubectl -n miniurl scale deployment/redirect-service --replicas=10
```

### 12.2 HPA Scaling

```bash
# View HPA status
kubectl -n miniurl get hpa

# View HPA events
kubectl -n miniurl describe hpa api-gateway-hpa
```

---

## 13. Updates

### 13.1 Update Service Image

```bash
# Update image
kubectl -n miniurl set image deployment/api-gateway api-gateway=miniurl/api-gateway:v1.0.0

# Watch rollout
kubectl -n miniurl rollout status deployment/api-gateway
```

### 13.2 Rollback

```bash
# Rollback to previous version
kubectl -n miniurl rollout undo deployment/api-gateway

# Rollback to specific revision
kubectl -n miniurl rollout undo deployment/api-gateway --to-revision=1
```

---

## 14. Backup and Restore

### 14.1 Backup MySQL

```bash
# Backup URL database
kubectl -n miniurl exec -it mysql-url-0 -- mysqldump -u root -prootpassword123 url_db > backup-url-$(date +%Y%m%d).sql

# Backup Identity database
kubectl -n miniurl exec -it mysql-identity-0 -- mysqldump -u root -prootpassword123 identity_db > backup-identity-$(date +%Y%m%d).sql

# Backup Feature database
kubectl -n miniurl exec -it mysql-feature-0 -- mysqldump -u root -prootpassword123 feature_db > backup-feature-$(date +%Y%m%d).sql

# Backup Analytics database
kubectl -n miniurl exec -it mysql-analytics-0 -- mysqldump -u root -prootpassword123 analytics_db > backup-analytics-$(date +%Y%m%d).sql
```

### 14.2 Restore MySQL

```bash
# Restore URL database
kubectl -n miniurl exec -it mysql-url-0 -- mysql -u root -prootpassword123 url_db < backup-url-20240101.sql

# Restore Identity database
kubectl -n miniurl exec -it mysql-identity-0 -- mysql -u root -prootpassword123 identity_db < backup-identity-20240101.sql

# Restore Feature database
kubectl -n miniurl exec -it mysql-feature-0 -- mysql -u root -prootpassword123 feature_db < backup-feature-20240101.sql

# Restore Analytics database
kubectl -n miniurl exec -it mysql-analytics-0 -- mysql -u root -prootpassword123 analytics_db < backup-analytics-20240101.sql
```

---

## 15. Cleanup

### 15.1 Delete Services

```bash
kubectl delete -f k8s/services/
kubectl delete -f k8s/hpa/
kubectl delete -f k8s/ingress/
```

### 15.2 Delete Infrastructure

```bash
kubectl delete -f k8s/infrastructure/
```

### 15.3 Delete Namespace

```bash
kubectl delete namespace miniurl
```

---

## 16. Production Checklist

- [ ] Update secrets with secure values
- [ ] Configure TLS for ingress
- [ ] Set up monitoring alerts
- [ ] Configure log aggregation
- [ ] Set up backup strategy
- [ ] Configure resource limits
- [ ] Set up HPA
- [ ] Test disaster recovery
- [ ] Configure CI/CD pipeline
- [ ] Set up health checks
- [ ] Configure readiness probes
- [ ] Set up rolling updates
- [ ] Configure pod disruption budgets
- [ ] Set up network policies
- [ ] Configure resource quotas