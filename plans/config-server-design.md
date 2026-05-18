# Spring Cloud Config Server — Architecture Decision

**Date:** 2026-04-30
**Decision:** NOT RECOMMENDED
**Rationale:** See [`plans/config-server-assessment.md`](plans/config-server-assessment.md)

---

## Decision Matrix

| Factor | Current Approach (env vars + application.yml) | Config Server | Winner |
|---|---|---|---|
| **K8s native** | ConfigMaps + Secrets = idiomatic | Adds non-K8s-native layer | Current |
| **Startup dependency** | None (self-contained) | Config server must be up first | Current |
| **Failure mode** | Service starts with defaults, env vars override | Config server down = service may fail to start | Current |
| **Debugging** | Read application.yml directly | Must query /{app}/{profile} endpoint | Current |
| **Test isolation** | Tests run standalone | Tests need config server or mock | Current |
| **Duplication** | ~40 lines across 8 services | Zero duplication | Config Server |
| **Auditability** | Config in same repo as code | Config in separate repo or path | Current |
| **Hot reload** | Requires restart (or K8s rollout) | @RefreshScope supports live reload | Config Server |
| **Secret handling** | K8s Secrets → env vars | Must encrypt in repo or use K8s Secrets anyway | Tie |

**Score:** Current approach wins 7-1-1.

---

## Why Config Server Is Overkill Here

1. **8 services × ~15 config lines each = 120 lines total.** The duplication is trivial.

2. **Kubernetes already solves the problem.** ConfigMaps provide centralized, version-controlled configuration. Secrets handle sensitive values. Environment variables bridge K8s → Spring Boot.

3. **No hot-reload requirement.** MiniURL services are stateless; a rolling restart is fast and safe. `@RefreshScope` adds complexity (bean re-initialization, cache invalidation) without clear benefit.

4. **Startup ordering is already complex.** Adding config-server as a prerequisite for all 8 services creates a new single point of failure.

5. **The real gaps are simple fixes:**
   - Replace hardcoded passwords with `${ENV_VAR}` references
   - Add `SPRING_PROFILES_ACTIVE=prod` to K8s deployments
   - Standardize Eureka env var naming

---

## What Would Config Server Look Like (If Implemented)

For documentation purposes only:

### Backend
- **Local/Dev:** Native file backend (`spring.profiles.active=native`) reading from `config-repo/` directory
- **Production:** Git backend pointing to a private GitHub repo (or native with ConfigMap-mounted files in K8s)

### Bootstrap
```yaml
# In each service's application.yml:
spring:
  config:
    import: optional:configserver:http://config-server:8888
  cloud:
    config:
      fail-fast: false  # Allow startup without config server
      retry:
        max-attempts: 6
        initial-interval: 1000
```

### Kubernetes Injection
```yaml
env:
  - name: SPRING_PROFILES_ACTIVE
    value: "prod"
  - name: SPRING_CONFIG_IMPORT
    value: "optional:configserver:http://config-server:8888"
```

### What Stays Local
- `spring.application.name` (must be local for bootstrap)
- `server.port` (or use `SERVER_PORT` env var)
- Test-specific config (`application-test.yml`)

### Secrets
- **Never** in config repo unless encrypted (Spring Cloud Config encryption with symmetric key)
- Preferred: Kubernetes Secrets mounted as env vars, referenced in config files as `${SECRET_NAME}`

---

## Recommended Actions (Instead of Config Server)

| Priority | Action | Effort |
|---|---|---|
| P1 | Replace hardcoded passwords in `url-service/application.yml` and `feature-service/application.yml` with `${ENV_VAR}` references | 5 min |
| P1 | Add `SPRING_PROFILES_ACTIVE=prod` to all K8s deployment manifests | 5 min |
| P2 | Standardize Eureka env var to `EUREKA_SERVER_URL` across all services | 15 min |
| P2 | Add `application-prod.yml` for services that need prod-specific settings (e.g., lower log levels, disable Swagger) | 30 min |
