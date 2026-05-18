# ⚠️ DEPRECATED — Use Helm Chart Instead

The files in this directory are **deprecated** and retained only for historical reference.

## What Replaced These

| Deprecated File | Replacement |
|----------------|-------------|
| `k8s/services/*.yaml` (per-service manifests) | [`helm/miniurl/templates/deployment.yaml`](../../helm/miniurl/templates/deployment.yaml) |
| `k8s/services/network-policies.yaml` | [`helm/miniurl/templates/networkpolicy.yaml`](../../helm/miniurl/templates/networkpolicy.yaml) |
| `k8s/services/pod-disruption-budgets.yaml` | [`helm/miniurl/templates/pdb.yaml`](../../helm/miniurl/templates/pdb.yaml) |
| `k8s/hpa/hpa.yaml` | [`helm/miniurl/templates/hpa.yaml`](../../helm/miniurl/templates/hpa.yaml) |
| `k8s/infrastructure/global-config.yaml` | [`helm/miniurl/templates/configmap.yaml`](../../helm/miniurl/templates/configmap.yaml) |
| `k8s/miniurl-all-in-one.yaml` | [`helm/miniurl/`](../../helm/miniurl/) (entire chart) |
| `.github/workflows/main-pipeline.yml` | [`deploy-dev.yml`](../../.github/workflows/deploy-dev.yml) + [`deploy-prod.yml`](../../.github/workflows/deploy-prod.yml) |

## Why

The Helm chart is now the **single source of truth** for all Kubernetes resources. Benefits:

- **No duplication**: One template generates resources for all 8 services
- **Environment-aware**: `values-{env}.yaml` overrides per environment
- **Immutable tags**: CI injects `IMAGE_TAG` — no mutable `latest` in dev/prod
- **Canary support**: `canary.enabled` + `canary.weight` for traffic splitting
- **Complete coverage**: Ingress, NetworkPolicies, PDBs, ResourceQuota, ServiceAccount all in one chart

## Migration Date

These files were deprecated on 2026-04-30 as part of the CI/CD architecture implementation.

## Removal

These files will be removed in a future release. If you still reference any of these, migrate to the Helm chart equivalents listed above.
