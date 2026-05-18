# CANARY RUNBOOK — MiniURL Microservices

**Date:** 2026-04-30
**Canary Version:** `<CANARY_IMAGE_TAG>` (e.g., `v2.1.0-canary`)
**Stable Version:** `<STABLE_IMAGE_TAG>` (e.g., `v2.0.0-stable`)
**Namespace:** `<NAMESPACE>` (e.g., `miniurl`)
**Ingress Host:** `<INGRESS_HOST>` (e.g., `api.miniurl.example.com`)
**Redirect Host:** `<REDIRECT_HOST>` (e.g., `r.miniurl.example.com`)

---

## 0. AUTOMATION PACKAGE (NEW — 2026-04-30)

Most manual steps in this runbook can now be automated. See [`deploy/README.md`](../deploy/README.md) for full documentation.

### Quick Automated Path

```bash
export NAMESPACE=miniurl
export MONITORING_NS=monitoring
export CANARY_IMAGE_TAG=v2.1.0-canary
export STABLE_IMAGE_TAG=v2.0.0-stable
export MYSQL_ROOT_PASSWORD="<secure-password>"

./scripts/deploy/preflight.sh          # Validate prerequisites
./scripts/deploy/bootstrap-infra.sh    # Redis + Kafka
./scripts/deploy/create-secrets.sh     # RSA keys + secrets
./scripts/deploy/run-migrations.sh     # DB migrations
./scripts/deploy/apply-monitoring.sh   # Alerts + blackbox-exporter
./scripts/deploy/capture-baseline.sh   # Baseline metrics
./scripts/deploy/start-canary.sh       # Phase 1 (10%)
```

### Script Reference

| Script | Replaces Runbook Section |
|---|---|
| [`scripts/deploy/preflight.sh`](../scripts/deploy/preflight.sh) | Section 1 (Pre-flight) |
| [`scripts/deploy/bootstrap-infra.sh`](../scripts/deploy/bootstrap-infra.sh) | Section 1 (Redis/Kafka health) |
| [`scripts/deploy/create-secrets.sh`](../scripts/deploy/create-secrets.sh) | Section 1 (Secrets verification) |
| [`scripts/deploy/run-migrations.sh`](../scripts/deploy/run-migrations.sh) | Section 1 (DB migrations) |
| [`scripts/deploy/apply-monitoring.sh`](../scripts/deploy/apply-monitoring.sh) | Section 1 (Monitoring config) |
| [`scripts/deploy/capture-baseline.sh`](../scripts/deploy/capture-baseline.sh) | Section 2 (Baseline capture) |
| [`scripts/deploy/start-canary.sh`](../scripts/deploy/start-canary.sh) | Sections 3-6 (Canary phases) |
| [`scripts/deploy/rollback-canary.sh`](../scripts/deploy/rollback-canary.sh) | Section 7 (Rollback) |

### Dry-Run Mode

All scripts support `DRY_RUN=true` to preview without executing:
```bash
DRY_RUN=true ./scripts/deploy/start-canary.sh
```

---

## 1. PRE-FLIGHT CHECKLIST

Complete every item before starting the canary. Check the box as each is verified.

### Infrastructure Health

- [ ] **Redis cluster is healthy**
  ```bash
  kubectl exec -n <NAMESPACE> deploy/redis -- redis-cli PING
  # Expected: PONG
  ```

- [ ] **Kafka brokers are healthy**
  ```bash
  kubectl exec -n <NAMESPACE> deploy/kafka -- kafka-broker-api-versions.sh --bootstrap-server localhost:9092 | head -5
  # Expected: list of broker versions, no errors
  ```

- [ ] **MySQL instances are reachable**
  ```bash
  kubectl exec -n <NAMESPACE> deploy/mysql-identity -- mysqladmin ping -u root -p$MYSQL_ROOT_PASSWORD
  kubectl exec -n <NAMESPACE> deploy/mysql-url -- mysqladmin ping -u root -p$MYSQL_ROOT_PASSWORD
  kubectl exec -n <NAMESPACE> deploy/mysql-feature -- mysqladmin ping -u root -p$MYSQL_ROOT_PASSWORD
  # Expected: mysqld is alive
  ```

- [ ] **Eureka Server is running and all stable services are registered**
  ```bash
  curl -s http://eureka-server.<NAMESPACE>.svc.cluster.local:8761/eureka/apps | grep -o '<name>[^<]*</name>'
  # Expected: API-GATEWAY, IDENTITY-SERVICE, URL-SERVICE, REDIRECT-SERVICE, FEATURE-SERVICE, NOTIFICATION-SERVICE
  ```

- [ ] **Prometheus is scraping metrics**
  ```bash
  kubectl port-forward -n monitoring svc/prometheus 9090:9090 &
  curl -s 'http://localhost:9090/api/v1/query?query=up' | jq '.data.result[] | {instance: .metric.instance, job: .metric.job}'
  # Expected: all services show up with value [1, "..."]
  ```

### Secrets & Config

- [ ] **RSA key pair deployed as K8s Secret**
  ```bash
  kubectl get secret -n <NAMESPACE> jwt-rsa-keys
  # Expected: NAME= jwt-rsa-keys, TYPE= Opaque, DATA= 2
  ```

- [ ] **DB secrets deployed**
  ```bash
  kubectl get secret -n <NAMESPACE> db-secrets
  # Expected: NAME= db-secrets, TYPE= Opaque
  ```

- [ ] **SMTP credentials deployed (if using real email)**
  ```bash
  kubectl get secret -n <NAMESPACE> smtp-credentials
  # Expected: NAME= smtp-credentials, TYPE= Opaque
  ```

- [ ] **Global ConfigMap is applied**
  ```bash
  kubectl get configmap -n <NAMESPACE> global-config -o yaml | head -30
  # Verify: REDIS_HOST, KAFKA_BOOTSTRAP_SERVERS, EUREKA_SERVER_URL, APP_BASE_URL, APP_UI_BASE_URL, SPRING_PROFILES_ACTIVE
  ```

- [ ] **Config hardening verified (2026-04-30)**
  - All services use `EUREKA_SERVER_URL` (standardized from `EUREKA_URL`/`GATEWAY_EUREKA_URL`)
  - All K8s deployments set `SPRING_PROFILES_ACTIVE=prod`
  - DB passwords use env vars (`URL_DB_PASSWORD`, `FEATURE_DB_PASSWORD`) not hardcoded values
  - All 8 services have `application-prod.yml` with reduced log levels
  - See [`plans/config-server-assessment.md`](plans/config-server-assessment.md) for details

### Database Migrations

- [ ] **Identity DB initialized**
  ```bash
  kubectl exec -n <NAMESPACE> deploy/mysql-identity -- mysql -u root -p$MYSQL_ROOT_PASSWORD identity_db -e "SELECT COUNT(*) AS user_count FROM users; SELECT COUNT(*) AS role_count FROM roles;"
  # Expected: role_count >= 2, user_count >= 1 (admin)
  ```

- [ ] **URL DB initialized**
  ```bash
  kubectl exec -n <NAMESPACE> deploy/mysql-url -- mysql -u root -p$MYSQL_ROOT_PASSWORD url_db -e "SHOW TABLES;"
  # Expected: urls, url_usage_limits
  ```

- [ ] **Feature DB initialized**
  ```bash
  kubectl exec -n <NAMESPACE> deploy/mysql-feature -- mysql -u root -p$MYSQL_ROOT_PASSWORD feature_db -e "SELECT COUNT(*) AS feature_count FROM features; SELECT COUNT(*) AS flag_count FROM feature_flags; SELECT COUNT(*) AS global_count FROM global_flags;"
  # Expected: feature_count >= 11, flag_count >= 16, global_count >= 3
  ```

### Admin User

- [ ] **Admin user exists and can authenticate**
  ```bash
  # Reset admin password if needed:
  kubectl exec -n <NAMESPACE> deploy/mysql-identity -- mysql -u root -p$MYSQL_ROOT_PASSWORD identity_db -e "
    UPDATE users SET password='<BCRYPT_HASH>', must_change_password=false, status='ACTIVE', failed_login_attempts=0, lockout_time=NULL
    WHERE username='admin';
  "
  ```

### Baseline Metrics (Capture Before Canary)

- [ ] **Record baseline P50/P95/P99 redirect latency**
  ```bash
  curl -s 'http://localhost:9090/api/v1/query?query=histogram_quantile(0.99,rate(http_server_requests_seconds_bucket{uri=~"/r/.*"}[5m]))' | jq '.data.result[0].value[1]'
  ```

- [ ] **Record baseline error rate**
  ```bash
  curl -s 'http://localhost:9090/api/v1/query?query=sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))/sum(rate(http_server_requests_seconds_count[5m]))' | jq '.data.result[0].value[1]'
  ```

- [ ] **Record baseline outbox backlog**
  ```bash
  kubectl exec -n <NAMESPACE> deploy/mysql-url -- mysql -u root -p$MYSQL_ROOT_PASSWORD url_db -e "SELECT COUNT(*) AS unprocessed FROM outbox WHERE processed = false;"
  ```

---

## 2. KAFKA TOPIC CREATION

Run these commands once before deploying any canary services.

```bash
# Create topics (idempotent — will not error if topics already exist)
kubectl exec -n <NAMESPACE> deploy/kafka -- kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --create --if-not-exists \
  --topic notifications \
  --partitions 3 \
  --replication-factor 1

kubectl exec -n <NAMESPACE> deploy/kafka -- kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --create --if-not-exists \
  --topic click-events \
  --partitions 6 \
  --replication-factor 1

kubectl exec -n <NAMESPACE> deploy/kafka -- kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --create --if-not-exists \
  --topic general-events \
  --partitions 3 \
  --replication-factor 1

# Verify topics exist
kubectl exec -n <NAMESPACE> deploy/kafka -- kafka-topics.sh \
  --bootstrap-server localhost:9092 --list
# Expected: click-events, general-events, notifications
```

---

## 3. EXACT DEPLOYMENT ORDER

Deploy services in this order. Wait for readiness before proceeding to the next.

### Phase 0: Infrastructure (if not already running)

```bash
# 1. Eureka Server
kubectl apply -f k8s/services/eureka-server.yaml
kubectl rollout status deployment/eureka-server -n <NAMESPACE> --timeout=120s

# 2. ConfigMap and Secrets (if not already applied)
kubectl apply -f k8s/infrastructure/global-config.yaml
```

### Phase 1: 5% Canary (Day 1, 0–4 hours)

**IMPORTANT:** Nginx ingress canary requires a **separate canary ingress resource** (not annotations on the main ingress). The canary ingress must have `nginx.ingress.kubernetes.io/canary: "true"` and `nginx.ingress.kubernetes.io/canary-weight` set, and must match the same `host` as the main ingress. See [nginx canary docs](https://kubernetes.github.io/ingress-nginx/user-guide/nginx-configuration/annotations/#canary).

```bash
# Step 0: Create canary deployments (one-time setup — skip if already created)
# These are lightweight copies of the stable deployments with "-canary" suffix.
# They share the same ConfigMaps, Secrets, and Service selectors.
# NOTE: No k8s/services/*-canary.yaml files exist yet. Create them by copying
# the stable deployment YAML and changing metadata.name to add "-canary" suffix,
# reducing replicas to 1, and adding a canary label for identification.

# Example for redirect-service-canary (repeat for api-gateway, url-service, etc.):
kubectl get deployment redirect-service -n <NAMESPACE> -o yaml | \
  sed 's/name: redirect-service$/name: redirect-service-canary/' | \
  sed 's/replicas: [0-9]*/replicas: 1/' | \
  kubectl apply -f -

# Step 1: Deploy canary redirect-service (1 pod)
kubectl set image deployment/redirect-service-canary \
  redirect-service=miniurl/redirect-service:<CANARY_IMAGE_TAG> \
  -n <NAMESPACE>

kubectl rollout status deployment/redirect-service-canary -n <NAMESPACE> --timeout=120s

# Step 2: Deploy canary api-gateway (1 pod)
kubectl set image deployment/api-gateway-canary \
  api-gateway=miniurl/api-gateway:<CANARY_IMAGE_TAG> \
  -n <NAMESPACE>

kubectl rollout status deployment/api-gateway-canary -n <NAMESPACE> --timeout=120s

# Step 3: Create canary ingress resource to route 5% to canary
# This is a SEPARATE ingress resource, not annotations on the main ingress.
cat <<EOF | kubectl apply -f -
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: miniurl-ingress-canary
  namespace: <NAMESPACE>
  annotations:
    nginx.ingress.kubernetes.io/canary: "true"
    nginx.ingress.kubernetes.io/canary-weight: "5"
spec:
  ingressClassName: nginx
  rules:
    - host: <INGRESS_HOST>
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: api-gateway-canary
                port:
                  number: 8080
EOF

# Step 4: Run smoke tests (see plans/canary-smoke-tests.md)
# Step 5: Monitor for 4 hours
```

### Phase 2: 25% Canary (Day 1, 4–8 hours)

```bash
# Step 1: Deploy canary url-service
kubectl set image deployment/url-service-canary \
  url-service=miniurl/url-service:<CANARY_IMAGE_TAG> \
  -n <NAMESPACE>

kubectl rollout status deployment/url-service-canary -n <NAMESPACE> --timeout=120s

# Step 2: Deploy canary identity-service
kubectl set image deployment/identity-service-canary \
  identity-service=miniurl/identity-service:<CANARY_IMAGE_TAG> \
  -n <NAMESPACE>

kubectl rollout status deployment/identity-service-canary -n <NAMESPACE> --timeout=120s

# Step 3: Deploy canary feature-service
kubectl set image deployment/feature-service-canary \
  feature-service=miniurl/feature-service:<CANARY_IMAGE_TAG> \
  -n <NAMESPACE>

kubectl rollout status deployment/feature-service-canary -n <NAMESPACE> --timeout=120s

# Step 4: Deploy canary notification-service
kubectl set image deployment/notification-service-canary \
  notification-service=miniurl/notification-service:<CANARY_IMAGE_TAG> \
  -n <NAMESPACE>

kubectl rollout status deployment/notification-service-canary -n <NAMESPACE> --timeout=120s

# Step 5: Increase canary weight to 25% (patch the canary ingress resource)
kubectl patch ingress miniurl-ingress-canary -n <NAMESPACE> --type='json' \
  -p='[{"op": "replace", "path": "/metadata/annotations/nginx.ingress.kubernetes.io~1canary-weight", "value": "25"}]'

# Step 6: Run smoke tests
# Step 7: Monitor for 4 hours
```

### Phase 3: 50% Canary (Day 2, 0–8 hours)

```bash
# Scale canary deployments to handle 50% traffic
kubectl scale deployment/redirect-service-canary --replicas=2 -n <NAMESPACE>
kubectl scale deployment/api-gateway-canary --replicas=2 -n <NAMESPACE>
kubectl scale deployment/url-service-canary --replicas=2 -n <NAMESPACE>
kubectl scale deployment/identity-service-canary --replicas=2 -n <NAMESPACE>

# Increase canary weight to 50% (patch the canary ingress resource)
kubectl patch ingress miniurl-ingress-canary -n <NAMESPACE> --type='json' \
  -p='[{"op": "replace", "path": "/metadata/annotations/nginx.ingress.kubernetes.io~1canary-weight", "value": "50"}]'

# Monitor for 8 hours
```

### Phase 4: 100% Promotion (Day 2, 8+ hours)

```bash
# Step 1: Promote canary images to stable deployments
kubectl set image deployment/redirect-service \
  redirect-service=miniurl/redirect-service:<CANARY_IMAGE_TAG> \
  -n <NAMESPACE>
kubectl set image deployment/api-gateway \
  api-gateway=miniurl/api-gateway:<CANARY_IMAGE_TAG> \
  -n <NAMESPACE>
kubectl set image deployment/url-service \
  url-service=miniurl/url-service:<CANARY_IMAGE_TAG> \
  -n <NAMESPACE>
kubectl set image deployment/identity-service \
  identity-service=miniurl/identity-service:<CANARY_IMAGE_TAG> \
  -n <NAMESPACE>
kubectl set image deployment/feature-service \
  feature-service=miniurl/feature-service:<CANARY_IMAGE_TAG> \
  -n <NAMESPACE>
kubectl set image deployment/notification-service \
  notification-service=miniurl/notification-service:<CANARY_IMAGE_TAG> \
  -n <NAMESPACE>

# Step 2: Wait for all stable deployments to roll out
kubectl rollout status deployment/redirect-service -n <NAMESPACE> --timeout=300s
kubectl rollout status deployment/api-gateway -n <NAMESPACE> --timeout=300s
kubectl rollout status deployment/url-service -n <NAMESPACE> --timeout=300s
kubectl rollout status deployment/identity-service -n <NAMESPACE> --timeout=300s
kubectl rollout status deployment/feature-service -n <NAMESPACE> --timeout=300s
kubectl rollout status deployment/notification-service -n <NAMESPACE> --timeout=300s

# Step 3: Remove canary ingress resource (all traffic now goes to stable)
kubectl delete ingress miniurl-ingress-canary -n <NAMESPACE> --ignore-not-found

# Step 4: Remove canary deployments
kubectl delete deployment/redirect-service-canary -n <NAMESPACE> --ignore-not-found
kubectl delete deployment/api-gateway-canary -n <NAMESPACE> --ignore-not-found
kubectl delete deployment/url-service-canary -n <NAMESPACE> --ignore-not-found
kubectl delete deployment/identity-service-canary -n <NAMESPACE> --ignore-not-found
kubectl delete deployment/feature-service-canary -n <NAMESPACE> --ignore-not-found
kubectl delete deployment/notification-service-canary -n <NAMESPACE> --ignore-not-found

# Step 5: Monitor for 24 hours
```

---

## 4. ENVIRONMENT VARIABLES

### Required Environment Variables (per service)

#### Global ConfigMap (`global-config`)

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: global-config
  namespace: <NAMESPACE>
data:
  EUREKA_SERVER_URL: "http://eureka-server:8761/eureka/"
  KAFKA_BOOTSTRAP_SERVERS: "kafka:9092"
  REDIS_HOST: "redis"
  REDIS_PORT: "6379"
  APP_BASE_URL: "https://<INGRESS_HOST>"
  APP_UI_BASE_URL: "https://<UI_HOST>"
  APP_CORS_ALLOWED_ORIGINS: "https://<UI_HOST>"
  APP_NAME: "MyURL"
```

#### Identity Service Specific

```yaml
# In deployment env:
- name: SPRING_DATASOURCE_URL
  value: "jdbc:mysql://mysql-identity:3306/identity_db?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true"
- name: SPRING_DATASOURCE_USERNAME
  value: "root"
- name: SPRING_DATASOURCE_PASSWORD
  valueFrom:
    secretKeyRef:
      name: db-secrets
      key: MYSQL_ROOT_PASSWORD
- name: JWT_RSA_PRIVATE_KEY_PATH
  value: "/etc/miniurl/keys/private.pem"
- name: JWT_RSA_PUBLIC_KEY_PATH
  value: "/etc/miniurl/keys/public.pem"
- name: JWT_RSA_KEY_ID
  value: "miniurl-rsa-key-1"
- name: JWT_EXPIRATION_MS
  value: "3600000"
- name: FEATURE_SERVICE_URL
  value: "http://feature-service:8081"
```

#### URL Service Specific

```yaml
- name: SPRING_DATASOURCE_URL
  value: "jdbc:mysql://mysql-url:3306/url_db?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true"
- name: SPRING_DATASOURCE_USERNAME
  value: "root"
- name: SPRING_DATASOURCE_PASSWORD
  valueFrom:
    secretKeyRef:
      name: db-secrets
      key: MYSQL_ROOT_PASSWORD
- name: REDIRECT_SERVICE_URL
  value: "http://redirect-service:8080"
```

#### Feature Service Specific

```yaml
- name: SPRING_DATASOURCE_URL
  value: "jdbc:mysql://mysql-feature:3306/feature_db?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true"
- name: SPRING_DATASOURCE_USERNAME
  value: "root"
- name: SPRING_DATASOURCE_PASSWORD
  valueFrom:
    secretKeyRef:
      name: db-secrets
      key: MYSQL_ROOT_PASSWORD
```

#### Notification Service Specific

```yaml
- name: SPRING_MAIL_HOST
  value: "<SMTP_HOST>"
- name: SPRING_MAIL_PORT
  value: "587"
- name: SPRING_MAIL_USERNAME
  valueFrom:
    secretKeyRef:
      name: smtp-credentials
      key: username
- name: SPRING_MAIL_PASSWORD
  valueFrom:
    secretKeyRef:
      name: smtp-credentials
      key: password
```

---

## 5. SECRETS CHECKLIST

| Secret Name | Keys | Purpose | How to Create |
|---|---|---|---|
| `jwt-rsa-keys` | `private.pem`, `public.pem` | JWT signing/verification | `kubectl create secret generic jwt-rsa-keys --from-file=private.pem --from-file=public.pem -n <NAMESPACE>` |
| `db-secrets` | `MYSQL_ROOT_PASSWORD` | MySQL access | `kubectl create secret generic db-secrets --from-literal=MYSQL_ROOT_PASSWORD='<PASSWORD>' -n <NAMESPACE>` |
| `smtp-credentials` | `username`, `password` | Email sending | `kubectl create secret generic smtp-credentials --from-literal=username='<USER>' --from-literal=password='<PASS>' -n <NAMESPACE>` |

### Generate RSA Keys (if not already done)

```bash
# Generate 2048-bit RSA key pair
openssl genrsa -out private.pem 2048
openssl rsa -in private.pem -pubout -out public.pem

# Create K8s secret
kubectl create secret generic jwt-rsa-keys \
  --from-file=private.pem \
  --from-file=public.pem \
  -n <NAMESPACE>
```

### Mount RSA keys in identity-service deployment

```yaml
# Add to deployment spec.template.spec:
volumes:
  - name: rsa-keys
    secret:
      secretName: jwt-rsa-keys
# Add to container:
volumeMounts:
  - name: rsa-keys
    mountPath: /etc/miniurl/keys
    readOnly: true
```

---

## 6. CACHE WARM-UP STEPS

Pre-seed Redis with the most frequently accessed short codes to minimize cold-start cache miss latency.

**How it works:** The redirect-service caches URL resolutions in Redis on first lookup. To warm the cache, we call the redirect-service's own `/r/{code}` endpoint for each popular short code. This triggers the normal cache-population path (Redis set on cache miss). Calling the URL service directly will NOT populate the redirect-service's Redis cache.

```bash
# Option A: Warm from production URL database (preferred)
# Step 1: Get top-N short codes from the URL service database
TOP_CODES=$(kubectl exec -n <NAMESPACE> deploy/mysql-url -- mysql -u root -p$MYSQL_ROOT_PASSWORD url_db -N -e "
  SELECT short_code FROM urls ORDER BY access_count DESC LIMIT 1000;
")

# Step 2: For each code, call the redirect-service to trigger cache population
# The redirect-service will fetch from URL service and cache in Redis automatically.
for CODE in $TOP_CODES; do
  # Call redirect-service internal endpoint to populate Redis cache
  curl -s -o /dev/null -w "%{http_code}" \
    http://redirect-service.<NAMESPACE>.svc.cluster.local:8080/r/$CODE
done

# Option B: Let cache warm naturally
# The redirect-service will populate Redis on first cache miss from real traffic.
# This is acceptable if Redis is fast and URL service can handle initial load.
# Expected: ~10-50ms additional latency for first ~1000 requests.
```

---

## 7. HEALTH CHECKS

### Per-Service Health Endpoints

```bash
# All services expose Actuator health endpoints

# API Gateway (port 8080)
curl -s http://api-gateway.<NAMESPACE>.svc.cluster.local:8080/actuator/health | jq .

# Identity Service (port 8081)
curl -s http://identity-service.<NAMESPACE>.svc.cluster.local:8081/actuator/health | jq .

# URL Service (port 8081)
curl -s http://url-service.<NAMESPACE>.svc.cluster.local:8081/actuator/health | jq .

# Redirect Service (port 8080)
curl -s http://redirect-service.<NAMESPACE>.svc.cluster.local:8080/actuator/health | jq .

# Feature Service (port 8081)
curl -s http://feature-service.<NAMESPACE>.svc.cluster.local:8081/actuator/health | jq .

# Notification Service (port 8080)
curl -s http://notification-service.<NAMESPACE>.svc.cluster.local:8080/actuator/health | jq .

# Eureka Server (port 8761)
curl -s http://eureka-server.<NAMESPACE>.svc.cluster.local:8761/actuator/health | jq .
```

### Expected Health Response

```json
{
  "status": "UP",
  "components": {
    "db": {"status": "UP"},
    "redis": {"status": "UP"},
    "kafka": {"status": "UP"},
    "discoveryComposite": {"status": "UP"}
  }
}
```

### Liveness vs Readiness

| Probe | Path | Purpose |
|---|---|---|
| Liveness | `/actuator/health/liveness` | Is the app alive? Restart if fails. |
| Readiness | `/actuator/health/readiness` | Can the app serve traffic? Remove from service if fails. |

---

## 8. SMOKE TESTS

Run after each canary phase. See [`plans/canary-smoke-tests.md`](plans/canary-smoke-tests.md) for the full test suite.

Quick verification:

```bash
# Health check
curl -s https://<INGRESS_HOST>/actuator/health | jq '.status'
# Expected: "UP"

# JWKS endpoint
curl -s https://<INGRESS_HOST>/.well-known/jwks.json | jq '.keys[0].kty'
# Expected: "RSA"

# Public feature flags (no auth required)
curl -s https://<INGRESS_HOST>/api/features/global | jq '.data | length'
# Expected: >= 3
```

---

## 9. MONITORING DASHBOARD CHECKLIST

Verify these dashboards are accessible and populated before and during canary.

### Prometheus (port-forward if needed)

```bash
kubectl port-forward -n monitoring svc/prometheus 9090:9090 &
```

- [ ] **Redirect Service Dashboard**
  - Query: `rate(http_server_requests_seconds_count{app="redirect-service"}[1m])`
  - Query: `histogram_quantile(0.99, rate(http_server_requests_seconds_bucket{app="redirect-service"}[5m]))`
  - Query: `rate(http_server_requests_seconds_count{app="redirect-service",status=~"5.."}[5m])`

- [ ] **Redis Dashboard**
  - Query: `redis_connected_clients`
  - Query: `redis_commands_processed_total`
  - Query: `rate(redis_keyspace_hits_total[5m]) / rate(redis_keyspace_misses_total[5m])`

- [ ] **Kafka Dashboard**
  - Query: `kafka_consumer_fetch_manager_records_lag`
  - Query: `kafka_producer_record_send_total`

- [ ] **Outbox Dashboard**
  - Query: `outbox_events_unprocessed` (custom metric if exposed)
  - Manual: `SELECT COUNT(*) FROM outbox WHERE processed = false AND created_at < NOW() - INTERVAL 5 MINUTE`

- [ ] **Circuit Breaker Dashboard**
  - Query: `resilience4j_circuitbreaker_state{name="emailService"}`
  - States: 0=CLOSED, 1=OPEN, 2=HALF_OPEN

### Grafana (if deployed)

```bash
kubectl port-forward -n monitoring svc/grafana 3000:3000 &
```

- [ ] Import dashboard JSON from `k8s/infrastructure/monitoring.yaml`
- [ ] Verify all panels render data

---

## 10. ROLLBACK COMMANDS

### Immediate Full Rollback (any phase)

**IMPORTANT:** `kubectl rollout undo` only works if the deployment was modified during the canary. Since the canary strategy modifies `*-canary` deployments (not the stable ones), the stable deployments may still be running the original image. In that case, simply deleting the canary ingress and canary deployments is sufficient — traffic returns to the unchanged stable deployments.

```bash
# Step 1: Remove canary ingress resource (stops routing traffic to canary)
kubectl delete ingress miniurl-ingress-canary -n <NAMESPACE> --ignore-not-found

# Step 2: If stable deployments WERE modified (e.g., during Phase 4 promotion),
# roll them back. Otherwise skip this step.
# Check if stable deployments were changed:
kubectl rollout history deployment/redirect-service -n <NAMESPACE> | tail -1

# If the latest revision is the canary image, roll back:
kubectl rollout undo deployment/redirect-service -n <NAMESPACE>
kubectl rollout undo deployment/api-gateway -n <NAMESPACE>
kubectl rollout undo deployment/url-service -n <NAMESPACE>
kubectl rollout undo deployment/identity-service -n <NAMESPACE>
kubectl rollout undo deployment/feature-service -n <NAMESPACE>
kubectl rollout undo deployment/notification-service -n <NAMESPACE>

# Step 3: Wait for rollback to complete (only if rollback was executed)
kubectl rollout status deployment/redirect-service -n <NAMESPACE> --timeout=300s
kubectl rollout status deployment/api-gateway -n <NAMESPACE> --timeout=300s
kubectl rollout status deployment/url-service -n <NAMESPACE> --timeout=300s
kubectl rollout status deployment/identity-service -n <NAMESPACE> --timeout=300s
kubectl rollout status deployment/feature-service -n <NAMESPACE> --timeout=300s
kubectl rollout status deployment/notification-service -n <NAMESPACE> --timeout=300s

# Step 4: Delete canary deployments
kubectl delete deployment/redirect-service-canary -n <NAMESPACE> --ignore-not-found
kubectl delete deployment/api-gateway-canary -n <NAMESPACE> --ignore-not-found
kubectl delete deployment/url-service-canary -n <NAMESPACE> --ignore-not-found
kubectl delete deployment/identity-service-canary -n <NAMESPACE> --ignore-not-found
kubectl delete deployment/feature-service-canary -n <NAMESPACE> --ignore-not-found
kubectl delete deployment/notification-service-canary -n <NAMESPACE> --ignore-not-found

# Step 5: Verify health after rollback
curl -s https://<INGRESS_HOST>/actuator/health | jq '.status'
# Expected: "UP"
```

### Partial Rollback (single service)

```bash
# Roll back only the redirect-service
# NOTE: rollout undo only works if the stable deployment was modified.
# If only the canary deployment was changed, simply delete the canary deployment
# and the canary ingress will stop routing to it.
kubectl rollout undo deployment/redirect-service -n <NAMESPACE>
kubectl rollout status deployment/redirect-service -n <NAMESPACE> --timeout=120s

# If the stable deployment was NOT modified, just scale down/delete the canary:
kubectl scale deployment/redirect-service-canary --replicas=0 -n <NAMESPACE>
```

### Redis Cache Invalidation After Rollback

```bash
# If URLs were modified during canary, flush redirect-service Redis cache
kubectl exec -n <NAMESPACE> deploy/redis -- redis-cli FLUSHDB
# Cache will repopulate naturally on next requests
```

---

## 11. READY TO EXECUTE CANARY — FINAL CHECKLIST

Before starting Phase 1, confirm ALL of the following:

- [ ] All pre-flight infrastructure health checks pass
- [ ] All secrets are deployed and verified
- [ ] All databases are initialized with seed data
- [ ] Admin user exists and password is known
- [ ] Kafka topics are created
- [ ] RSA keys are deployed and JWKS endpoint is reachable
- [ ] Baseline metrics are captured
- [ ] Prometheus alerts are configured (see [`plans/canary-monitoring-alerts.md`](plans/canary-monitoring-alerts.md))
- [ ] Smoke test script is ready (see [`plans/canary-smoke-tests.md`](plans/canary-smoke-tests.md))
- [ ] Rollback commands are documented and tested in a non-prod environment
- [ ] Team is on standby for the full canary window
- [ ] Communication channel (Slack/Teams) is set up for canary status updates

**When all boxes are checked, proceed to Phase 1 deployment.**
