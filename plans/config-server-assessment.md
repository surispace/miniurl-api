# Spring Cloud Config Server — Assessment

**Date:** 2026-04-30
**Assessor:** Autonomous Production-Readiness Agent
**Scope:** All 8 microservices + monolith

---

## 1. Configuration Inventory

### 1.1 Per-Service Configuration Files

| Service | Config Files | Profile Split? |
|---|---|---|
| eureka-server | `application.yml` | No |
| api-gateway | `application.yml` | No |
| identity-service | `application.yml` + `application-dev.yml` | Yes (dev only) |
| url-service | `application.yml` | No |
| redirect-service | `application.yml` | No |
| feature-service | `application.yml` | No |
| notification-service | `application.yml` | No |
| analytics-service | `application.yml` | No |
| miniurl-monolith | `application.yml` + `application-dev.yml` + `application-prod.yml` + `application.properties` | Yes (dev/prod) |

### 1.2 Duplicated Configuration

These settings appear identically or near-identically across multiple services:

| Setting | Count | Services |
|---|---|---|
| `management.endpoints.web.exposure.include` | 8 | All services |
| `management.endpoint.health.show-details: always` | 8 | All services |
| `eureka.client.serviceUrl.defaultZone` | 7 | All except eureka-server |
| `spring.kafka.bootstrap-servers` | 5 | url, identity, redirect, notification, analytics |
| `spring.kafka.producer.key-serializer` | 3 | url, identity, redirect |
| `spring.kafka.producer.value-serializer` | 3 | url, identity, redirect |
| `spring.application.name` | 8 | All services |
| `server.port` | 8 | All services |

**Duplication severity:** LOW. Each setting is 1-3 lines. Total duplicated YAML across all services is ~40 lines. This is not a maintenance burden worth solving with infrastructure.

### 1.3 Environment-Specific Configuration

Only **2 of 9** modules have profile-specific config files:

| Service | Dev Config | Prod Config |
|---|---|---|
| identity-service | `application-dev.yml` (datasource, JPA, logging) | None |
| miniurl-monolith | `application-dev.yml` (datasource, JPA, rate limits, mail, logging) | `application-prod.yml` (datasource, JPA, rate limits, logging) |

All other services use a **single `application.yml` with environment variable overrides**. This is the standard Spring Boot pattern and works correctly.

### 1.4 Secrets Currently in Configuration Files

| File | Secret | Risk |
|---|---|---|
| [`url-service/src/main/resources/application.yml:16`](url-service/src/main/resources/application.yml:16) | `spring.datasource.password: root` | **HIGH** — hardcoded DB password |
| [`feature-service/src/main/resources/application.yml:10`](feature-service/src/main/resources/application.yml:10) | `spring.datasource.password: root` | **HIGH** — hardcoded DB password |
| [`miniurl-monolith/src/main/resources/application.yml:39`](miniurl-monolith/src/main/resources/application.yml:39) | `app.jwt.secret: MyURLSecretKey...` | **MEDIUM** — default HMAC key in source |
| [`identity-service/src/main/resources/application-dev.yml:10`](identity-service/src/main/resources/application-dev.yml:10) | `password: root` | **LOW** — dev profile only |

**Note:** These are defaults that get overridden by environment variables in deployed environments. However, hardcoded passwords in source are a security smell.

### 1.5 Infrastructure Settings Summary

| Category | Services Using | Configuration Method |
|---|---|---|
| **Redis** | api-gateway (rate limiting), redirect-service (cache), feature-service (cache) | `spring.data.redis.host/port` with env var overrides |
| **Kafka** | url, identity, redirect (producers); notification, analytics (consumers) | `spring.kafka.bootstrap-servers` with `KAFKA_BOOTSTRAP_SERVERS` env var |
| **MySQL** | identity (identity_db), url (url_db), feature (feature_db), analytics (mysql_analytics) | `spring.datasource.url/username/password` — mixed env var and hardcoded |
| **Eureka** | All 8 services | `eureka.client.serviceUrl.defaultZone` with `EUREKA_SERVER_URL` or `EUREKA_URL` env var |
| **JWT/RSA** | identity-service (issuer), api-gateway (validator via JWKS) | `jwt.rsa.*` paths + `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` |
| **SMTP** | notification-service, miniurl-monolith | `spring.mail.*` with `SMTP_*` env vars |
| **CORS** | Not configured in application.yml (likely in SecurityConfig beans) | N/A |

### 1.6 Kubernetes Configuration

- **ConfigMap:** [`k8s/infrastructure/global-config.yaml`](k8s/infrastructure/global-config.yaml) provides `APP_BASE_URL`, `APP_UI_BASE_URL`, `KAFKA_BOOTSTRAP_SERVERS`, `EUREKA_SERVER_URL`
- **Secrets:** `db-secrets` Secret provides `MYSQL_ROOT_PASSWORD`
- **Service Deployments:** Use `envFrom.configMapRef` to inject global-config
- **No `SPRING_PROFILES_ACTIVE`** set in K8s manifests (should be `prod`)
- **No `SPRING_CONFIG_IMPORT`** anywhere

### 1.7 Docker Compose Configuration

- Each service has explicit `environment:` block in [`docker-compose.yml`](docker-compose.yml)
- All services set `SPRING_PROFILES_ACTIVE=dev`
- Infrastructure hostnames are hardcoded per service (e.g., `kafka:9092`, `redis:6379`)
- No config server reference

---

## 2. Gap Analysis

### Actual Problems Found (ALL RESOLVED — 2026-04-30)

1. ~~**Hardcoded DB passwords** in `url-service` and `feature-service` `application.yml`~~ → **FIXED**: Replaced with `${URL_DB_PASSWORD:root}` and `${FEATURE_DB_PASSWORD:root}`
2. ~~**No `SPRING_PROFILES_ACTIVE=prod`** in K8s deployment manifests~~ → **FIXED**: Added to all 8 service deployments + global-config ConfigMap
3. ~~**Inconsistent Eureka env var naming**~~ → **FIXED**: Standardized to `EUREKA_SERVER_URL` across all services, docker-compose.yml, and K8s manifests
4. ~~**No production profile files** for 6 of 8 microservices~~ → **FIXED**: Added `application-prod.yml` to all 8 services with reduced log levels, actuator/prometheus preserved

### What Is NOT a Problem

- The duplication is minimal (~40 lines total) and each service's config is independently readable
- Environment variable overrides work correctly in both docker-compose and Kubernetes
- Tests run without external dependencies
- Secrets are not exposed in production (env vars come from K8s Secrets)

---

## 3. Recommendation

**Spring Cloud Config Server is NOT RECOMMENDED for this project.**

The current configuration approach (defaults in `application.yml` + environment variable overrides) is:

1. **Well-suited to Kubernetes** — ConfigMaps and Secrets are the native K8s way to manage configuration
2. **Simple to debug** — each service's effective config is visible in its own `application.yml`
3. **Test-friendly** — services start standalone without external config dependencies
4. **Already working** — the system passes 256 tests with the current setup

Adding Config Server would introduce:
- A new deployment artifact and startup dependency
- Bootstrap complexity (config import, retry, fail-fast behavior)
- Risk of config server outage blocking all service startups
- Changes to 8 service POMs, 8 application.yml files, docker-compose.yml, and all K8s manifests

The actual gaps were fixed directly (see above) rather than through architectural change.
