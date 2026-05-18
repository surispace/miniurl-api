# CANARY MONITORING ALERTS — MiniURL Microservices

**Purpose:** Prometheus alerting rules for the canary deployment window.
**Alertmanager Integration:** Route to `<ALERTMANAGER_ENDPOINT>` or Slack webhook.
**Severity Levels:** `critical` = immediate rollback, `warning` = investigate, pause canary expansion.

> **⚠️ PRE-CANARY ACTION REQUIRED:** The following custom metrics are referenced in alerts below but do **not** yet exist in the application code. They must be implemented and exposed via `/actuator/prometheus` before the canary begins:
>
> | Metric | Used In Alert | Implementation Required |
> |---|---|---|
> | `redirect_redis_fallback_total` | `CanaryRedisHighFallbackRate`, `CanaryRedisFallbackDetected` | Add a `Counter` in [`RedirectService.java`](../../redirect-service/src/main/java/com/miniurl/redirect/service/RedirectService.java) incremented in `.onErrorResume()` blocks |
> | `outbox_events_unprocessed` | `CanaryOutboxBacklogGrowing` | Add a `Gauge` in [`OutboxRelay.java`](../../url-service/src/main/java/com/miniurl/url/service/OutboxRelay.java) updated each cycle with `outboxRepository.countByProcessedFalse()` |
> | `outbox_events_age_seconds` | `CanaryOutboxEventsAging` | Add a `Gauge` in [`OutboxRelay.java`](../../url-service/src/main/java/com/miniurl/url/service/OutboxRelay.java) updated with age of oldest unprocessed event |
>
> **Without these metrics, the Redis fallback and outbox backlog alerts will NOT fire.** The alerts using standard Spring Boot / Kafka / Kubernetes metrics (error rate, latency, consumer lag, circuit breakers, HPA, DB pools) will work without changes.
>
> **Blackbox Exporter:** The `CanaryJwksEndpointDown` and `CanaryJwksKeyMissing` alerts require a blackbox-exporter module configured to probe `/.well-known/jwks.json` with `fail_if_body_not_matches_regexp: ['"kty":"RSA"']`.

---

## Prometheus Alert Rules

Save as `k8s/infrastructure/canary-alerts.yaml` and apply with:
```bash
kubectl apply -f k8s/infrastructure/canary-alerts.yaml -n monitoring
```

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: canary-alert-rules
  namespace: monitoring
  labels:
    app: prometheus
    role: alert-rules
data:
  canary-alerts.yml: |
    groups:
      - name: canary_redirect_service
        rules:
          # ============================================
          # REDIRECT SERVICE — ERROR RATE
          # ============================================
          - alert: CanaryRedirectHighErrorRate
            expr: |
              sum(rate(http_server_requests_seconds_count{app="redirect-service",status=~"5.."}[5m]))
              /
              sum(rate(http_server_requests_seconds_count{app="redirect-service"}[5m]))
              > 0.005
            for: 2m
            labels:
              severity: critical
              service: redirect-service
              canary: "true"
            annotations:
              summary: "Redirect service error rate > 0.5%"
              description: "Redirect service 5xx error rate is {{ $value | humanizePercentage }} over the last 5 minutes. Threshold: 0.5%."
              runbook: "Check redirect-service logs. If persistent, execute ROLLBACK per plans/canary-runbook.md Section 10."
              rollback_trigger: "RT-1"

          - alert: CanaryRedirectElevatedErrorRate
            expr: |
              sum(rate(http_server_requests_seconds_count{app="redirect-service",status=~"5.."}[5m]))
              /
              sum(rate(http_server_requests_seconds_count{app="redirect-service"}[5m]))
              > 0.001
            for: 5m
            labels:
              severity: warning
              service: redirect-service
              canary: "true"
            annotations:
              summary: "Redirect service error rate > 0.1%"
              description: "Redirect service 5xx error rate is {{ $value | humanizePercentage }} over the last 5 minutes. Threshold: 0.1%."
              runbook: "Investigate redirect-service logs for transient errors. Do not expand canary until resolved."

          # ============================================
          # REDIRECT SERVICE — LATENCY
          # ============================================
          - alert: CanaryRedirectP99LatencyHigh
            expr: |
              histogram_quantile(0.99,
                rate(http_server_requests_seconds_bucket{app="redirect-service",uri=~"/r/.*"}[5m])
              ) > 0.2
            for: 5m
            labels:
              severity: critical
              service: redirect-service
              canary: "true"
            annotations:
              summary: "Redirect P99 latency > 200ms"
              description: "Redirect service P99 latency is {{ $value }}s over the last 5 minutes. Threshold: 200ms."
              runbook: "Check Redis health and URL service latency. If persistent, execute ROLLBACK per plans/canary-runbook.md Section 10."
              rollback_trigger: "RT-2"

          - alert: CanaryRedirectP95LatencyElevated
            expr: |
              histogram_quantile(0.95,
                rate(http_server_requests_seconds_bucket{app="redirect-service",uri=~"/r/.*"}[5m])
              ) > 0.1
            for: 5m
            labels:
              severity: warning
              service: redirect-service
              canary: "true"
            annotations:
              summary: "Redirect P95 latency > 100ms"
              description: "Redirect service P95 latency is {{ $value }}s over the last 5 minutes. Threshold: 100ms."
              runbook: "Monitor Redis cache hit ratio. Consider cache warm-up if miss rate is high."

          - alert: CanaryRedirectLatencySpike
            expr: |
              (
                histogram_quantile(0.99,
                  rate(http_server_requests_seconds_bucket{app="redirect-service",uri=~"/r/.*"}[5m])
                )
                /
                histogram_quantile(0.99,
                  rate(http_server_requests_seconds_bucket{app="redirect-service",uri=~"/r/.*"}[5m] offset 30m)
                )
              ) > 2.0
            for: 5m
            labels:
              severity: warning
              service: redirect-service
              canary: "true"
            annotations:
              summary: "Redirect P99 latency > 2× baseline"
              description: "Redirect P99 latency is {{ $value }}× the 30-minute baseline. Investigate immediately."

          # ============================================
          # REDIRECT SERVICE — REDIS FALLBACK RATE
          # ============================================
          - alert: CanaryRedisHighFallbackRate
            expr: |
              rate(redirect_redis_fallback_total[5m])
              /
              rate(http_server_requests_seconds_count{app="redirect-service",uri=~"/r/.*"}[5m])
              > 0.10
            for: 5m
            labels:
              severity: critical
              service: redirect-service
              canary: "true"
            annotations:
              summary: "Redis fallback rate > 10%"
              description: "{{ $value | humanizePercentage }} of redirect requests are falling back from Redis to URL service. Redis may be unhealthy."
              runbook: "Check Redis pod health and connectivity. If Redis is down, redirects still work but are slower. Rollback if Redis cannot be recovered."
              rollback_trigger: "RT-3"

          - alert: CanaryRedisFallbackDetected
            expr: |
              rate(redirect_redis_fallback_total[5m]) > 0
            for: 2m
            labels:
              severity: warning
              service: redirect-service
              canary: "true"
            annotations:
              summary: "Redis fallback detected"
              description: "Redis fallback is occurring at rate {{ $value }}/s. Investigate Redis connectivity."

      # ============================================
      # KAFKA / OUTBOX
      # ============================================
      - name: canary_kafka_outbox
        rules:
          - alert: CanaryKafkaConsumerLagHigh
            expr: |
              sum(kafka_consumer_fetch_manager_records_lag{group=~".*notification.*|.*analytics.*"}) > 1000
            for: 5m
            labels:
              severity: warning
              service: kafka
              canary: "true"
            annotations:
              summary: "Kafka consumer lag > 1000 records"
              description: "Total consumer lag is {{ $value }} records. Notifications or analytics may be delayed."
              runbook: "Check Kafka broker health and consumer group status. Pause canary expansion if lag is growing."
              rollback_trigger: "RT-4 (indirect — monitor outbox backlog)"

          - alert: CanaryOutboxBacklogGrowing
            expr: |
              # This requires a custom metric or external check.
              # Alternative: use a recording rule from a DB query exporter.
              # Placeholder for outbox_events_unprocessed gauge.
              outbox_events_unprocessed{service="url-service"} > 50
            for: 5m
            labels:
              severity: critical
              service: url-service
              canary: "true"
            annotations:
              summary: "Outbox backlog > 50 unprocessed events"
              description: "URL service outbox has {{ $value }} unprocessed events. Events are not being published to Kafka."
              runbook: "Check Kafka broker health. Check OutboxRelay logs for errors. If Kafka is down, events are safe in DB. Rollback if Kafka cannot be recovered."
              rollback_trigger: "RT-4"

          - alert: CanaryOutboxEventsAging
            expr: |
              # Events older than 5 minutes still unprocessed
              outbox_events_age_seconds{service="url-service"} > 300
            for: 5m
            labels:
              severity: warning
              service: url-service
              canary: "true"
            annotations:
              summary: "Outbox events aging > 5 minutes"
              description: "Oldest unprocessed outbox event is {{ $value }}s old. Outbox relay may be stuck."
              runbook: "Check OutboxRelay scheduled task. Verify Kafka connectivity from url-service pods."

      # ============================================
      # JWKS / AUTHENTICATION
      # ============================================
      - name: canary_jwks_auth
        rules:
          - alert: CanaryJwksEndpointDown
            expr: |
              probe_success{job="blackbox",target=~".*/.well-known/jwks.json"} == 0
            for: 1m
            labels:
              severity: critical
              service: identity-service
              canary: "true"
            annotations:
              summary: "JWKS endpoint is DOWN"
              description: "The JWKS endpoint at /.well-known/jwks.json is not responding. API Gateway cannot validate JWT tokens."
              runbook: "IMMEDIATE ROLLBACK. Without JWKS, all authenticated requests will fail. Execute ROLLBACK per plans/canary-runbook.md Section 10."
              rollback_trigger: "RT-5"

          - alert: CanaryJwksKeyMissing
            expr: |
              # Check that JWKS response contains at least one key.
              # Uses blackbox exporter with fail_if_body_not_matches_regexp to verify
              # the JWKS response contains '"kty":"RSA"'.
              # Requires blackbox-exporter module configured with:
              #   fail_if_body_not_matches_regexp: ['"kty":"RSA"']
              probe_success{job="blackbox",target=~".*/.well-known/jwks.json"} == 0
            for: 2m
            labels:
              severity: critical
              service: identity-service
              canary: "true"
            annotations:
              summary: "JWKS endpoint returns no valid RSA keys"
              description: "The JWKS endpoint is reachable but does not contain valid RSA keys. JWT validation will fail."
              runbook: "Check identity-service KeyService initialization. Verify RSA key files are mounted correctly. Rollback if not immediately fixable."
              rollback_trigger: "RT-5"

          - alert: CanaryAuthHighFailureRate
            expr: |
              sum(rate(http_server_requests_seconds_count{app="identity-service",uri=~"/api/auth/.*",status=~"4..|5.."}[5m]))
              /
              sum(rate(http_server_requests_seconds_count{app="identity-service",uri=~"/api/auth/.*"}[5m]))
              > 0.05
            for: 5m
            labels:
              severity: warning
              service: identity-service
              canary: "true"
            annotations:
              summary: "Auth endpoint failure rate > 5%"
              description: "Authentication endpoints have {{ $value | humanizePercentage }} failure rate."
              runbook: "Check identity-service logs. Verify DB connectivity. Pause canary expansion."

      # ============================================
      # CIRCUIT BREAKERS
      # ============================================
      - name: canary_circuit_breakers
        rules:
          - alert: CanaryCircuitBreakerOpen
            expr: |
              resilience4j_circuitbreaker_state{name="emailService"} == 1
            for: 30s
            labels:
              severity: warning
              service: notification-service
              canary: "true"
            annotations:
              summary: "EmailService circuit breaker is OPEN"
              description: "The email service circuit breaker has been open for > 30 seconds. Emails are not being sent."
              runbook: "Check SMTP server health. Emails will be queued/dropped per fallback. Non-critical for redirect path. Rollback if email delivery is required."
              rollback_trigger: "RT-6"

          - alert: CanaryCircuitBreakerHalfOpen
            expr: |
              resilience4j_circuitbreaker_state{name="emailService"} == 2
            for: 2m
            labels:
              severity: warning
              service: notification-service
              canary: "true"
            annotations:
              summary: "EmailService circuit breaker is HALF_OPEN"
              description: "The email service circuit breaker is testing recovery. Monitor for re-opening."

      # ============================================
      # HPA / SCALING
      # ============================================
      - name: canary_hpa_scaling
        rules:
          - alert: CanaryHpaNotScaling
            expr: |
              (
                sum(rate(container_cpu_usage_seconds_total{container="redirect-service"}[5m]))
                /
                sum(kube_pod_container_resource_requests{resource="cpu",container="redirect-service"})
              ) > 0.85
            for: 5m
            labels:
              severity: warning
              service: redirect-service
              canary: "true"
            annotations:
              summary: "Redirect service CPU > 85% without scale-up"
              description: "Redirect service CPU utilization is {{ $value | humanizePercentage }} but HPA has not scaled up."
              runbook: "Check HPA status: kubectl describe hpa redirect-service-hpa -n <NAMESPACE>. Manually scale if needed: kubectl scale deployment/redirect-service --replicas=5 -n <NAMESPACE>."
              rollback_trigger: "RT-7"

          - alert: CanaryPodCrashLooping
            expr: |
              rate(kube_pod_container_status_restarts_total{container=~".*canary.*|redirect-service|api-gateway"}[5m]) > 0
            for: 2m
            labels:
              severity: critical
              service: any
              canary: "true"
            annotations:
              summary: "Canary pod is crash-looping"
              description: "Container {{ $labels.container }} in pod {{ $labels.pod }} is restarting."
              runbook: "Check pod logs: kubectl logs -n <NAMESPACE> {{ $labels.pod }} --previous. Rollback if cause cannot be immediately identified."
              rollback_trigger: "RT-1 (if causing errors)"

      # ============================================
      # DATABASE CONNECTIONS
      # ============================================
      - name: canary_database
        rules:
          - alert: CanaryDbConnectionPoolExhaustion
            expr: |
              hikaricp_connections_active{pool="*"}
              /
              hikaricp_connections_max{pool="*"}
              > 0.90
            for: 2m
            labels:
              severity: critical
              service: any
              canary: "true"
            annotations:
              summary: "DB connection pool > 90% utilized"
              description: "Connection pool {{ $labels.pool }} is at {{ $value | humanizePercentage }} of max."
              runbook: "Check for connection leaks. Increase pool size if needed. Rollback if connections are exhausted."
              rollback_trigger: "RT-8"

      # ============================================
      # FEATURE SERVICE INTEROP
      # ============================================
      - name: canary_feature_service
        rules:
          - alert: CanaryFeatureServiceDown
            expr: |
              up{job="feature-service"} == 0
            for: 2m
            labels:
              severity: warning
              service: feature-service
              canary: "true"
            annotations:
              summary: "Feature service is DOWN"
              description: "Feature service is not reachable. Self-invite and feature flag checks will fail-closed (return false/disabled)."
              runbook: "Check feature-service pod status. Non-critical for redirect path. Identity service will gracefully degrade."

          - alert: CanaryFeatureServiceHighLatency
            expr: |
              histogram_quantile(0.99,
                rate(http_server_requests_seconds_bucket{app="feature-service"}[5m])
              ) > 1.0
            for: 5m
            labels:
              severity: warning
              service: feature-service
              canary: "true"
            annotations:
              summary: "Feature service P99 latency > 1s"
              description: "Feature service P99 latency is {{ $value }}s. May cause timeouts in identity-service feature checks."
              runbook: "Check feature-service DB health. Identity service has timeout on feature service calls."
```

---

## Threshold Summary

| # | Metric | Warning | Critical | Rollback Trigger |
|---|---|---|---|---|
| RT-1 | Redirect 5xx error rate | >0.1% for 5m | >0.5% for 2m | RT-1 |
| RT-2 | Redirect P99 latency | >100ms for 5m | >200ms for 5m | RT-2 |
| RT-2b | Redirect latency vs baseline | >2× baseline | — | RT-2 |
| RT-3 | Redis fallback rate | Any for 2m | >10% for 5m | RT-3 |
| RT-4 | Outbox backlog | Events aging >5m | >50 unprocessed for 5m | RT-4 |
| RT-4b | Kafka consumer lag | >1000 for 5m | — | RT-4 (indirect) |
| RT-5 | JWKS endpoint | — | Down for 1m | RT-5 |
| RT-5b | JWKS keys | — | No keys for 2m | RT-5 |
| RT-6 | Circuit breaker open | Open >30s | — | RT-6 |
| RT-7 | HPA not scaling | CPU >85% for 5m | — | RT-7 |
| RT-8 | DB connection pool | — | >90% for 2m | RT-8 |
| — | Pod crash looping | — | Any restart for 2m | RT-1 |
| — | Auth failure rate | >5% for 5m | — | Pause canary |
| — | Feature service down | Down for 2m | — | Non-critical |

---

## Manual Monitoring Commands

Run these periodically during each canary phase:

### Redirect Service Health

```bash
# Error rate (last 5 minutes)
curl -s 'http://prometheus:9090/api/v1/query?query=sum(rate(http_server_requests_seconds_count{app="redirect-service",status=~"5.."}[5m]))/sum(rate(http_server_requests_seconds_count{app="redirect-service"}[5m]))' | jq '.data.result[0].value[1]'

# P99 latency
curl -s 'http://prometheus:9090/api/v1/query?query=histogram_quantile(0.99,rate(http_server_requests_seconds_bucket{app="redirect-service",uri=~"/r/.*"}[5m]))' | jq '.data.result[0].value[1]'

# Request rate
curl -s 'http://prometheus:9090/api/v1/query?query=sum(rate(http_server_requests_seconds_count{app="redirect-service",uri=~"/r/.*"}[5m]))' | jq '.data.result[0].value[1]'
```

### Outbox Backlog (Manual DB Query)

```bash
# URL service outbox
kubectl exec -n <NAMESPACE> deploy/mysql-url -- mysql -u root -p$MYSQL_ROOT_PASSWORD url_db -e "
  SELECT
    COUNT(*) AS unprocessed,
    MIN(TIMESTAMPDIFF(SECOND, created_at, NOW())) AS oldest_age_seconds
  FROM outbox WHERE processed = false;
"

# Identity service outbox
kubectl exec -n <NAMESPACE> deploy/mysql-identity -- mysql -u root -p$MYSQL_ROOT_PASSWORD identity_db -e "
  SELECT
    COUNT(*) AS unprocessed,
    MIN(TIMESTAMPDIFF(SECOND, created_at, NOW())) AS oldest_age_seconds
  FROM outbox WHERE processed = false;
"
```

### Circuit Breaker State

```bash
# Check email service circuit breaker
curl -s http://notification-service.<NAMESPACE>.svc.cluster.local:8080/actuator/circuitbreakers | jq '.circuitBreakers.emailService'
```

### Redis Health

```bash
# Cache hit ratio
curl -s 'http://prometheus:9090/api/v1/query?query=rate(redis_keyspace_hits_total[5m])/(rate(redis_keyspace_hits_total[5m])+rate(redis_keyspace_misses_total[5m]))' | jq '.data.result[0].value[1]'

# Connected clients
kubectl exec -n <NAMESPACE> deploy/redis -- redis-cli CLIENT LIST | wc -l
```

---

## Alertmanager Configuration (Example)

```yaml
# k8s/infrastructure/alertmanager-config.yaml
apiVersion: v1
kind: Secret
metadata:
  name: alertmanager-config
  namespace: monitoring
stringData:
  alertmanager.yml: |
    global:
      slack_api_url: '<SLACK_WEBHOOK_URL>'

    route:
      receiver: 'slack-canary'
      group_by: ['alertname', 'service']
      group_wait: 10s
      group_interval: 30s
      repeat_interval: 5m
      routes:
        - match:
            severity: critical
          receiver: 'slack-canary-critical'
          repeat_interval: 1m

    receivers:
      - name: 'slack-canary'
        slack_configs:
          - channel: '#canary-alerts'
            title: '{{ .GroupLabels.alertname }}'
            text: '{{ range .Alerts }}{{ .Annotations.description }}\n{{ end }}'

      - name: 'slack-canary-critical'
        slack_configs:
          - channel: '#canary-critical'
            title: ':red_circle: CRITICAL: {{ .GroupLabels.alertname }}'
            text: '{{ range .Alerts }}{{ .Annotations.description }}\nRunbook: {{ .Annotations.runbook }}\n{{ end }}'
```

---

## Canary Phase Monitoring Checklist

At each phase transition, verify:

- [ ] No critical alerts firing
- [ ] Warning alerts are acknowledged and investigated
- [ ] Redirect error rate within threshold
- [ ] Redirect P99 latency within threshold
- [ ] Redis fallback rate at 0% or near-zero
- [ ] Outbox backlog at 0 or stable
- [ ] Kafka consumer lag within threshold
- [ ] JWKS endpoint healthy
- [ ] Circuit breakers closed
- [ ] HPA functioning (check `kubectl describe hpa`)
- [ ] DB connection pools healthy
- [ ] Smoke tests passing (see [`plans/canary-smoke-tests.md`](plans/canary-smoke-tests.md))

**If any critical alert fires: execute rollback immediately.**
**If any warning alert persists >10 minutes: pause canary expansion, investigate.**
