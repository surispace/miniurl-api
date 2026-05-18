# MiniURL — Home Server K3s Deployment Guide

Deploy MiniURL to a home server running K3s. GitHub Actions handles deployments
via a self-hosted runner installed on the same machine. No SSH, no webhooks,
no inbound connections from GitHub to your server.

## Table of Contents

1. [Why SSH/Webhooks Are Not Needed](#1-why-ssh--webhooks-are-not-needed)
2. [Self-hosted Runner Network Model](#2-self-hosted-runner-network-model)
3. [Hardware Requirements](#3-hardware-requirements)
4. [OS & Firewall Setup](#4-os--firewall-setup)
5. [Install K3s](#5-install-k3s)
6. [Install Required Tools on the Runner Host](#6-install-required-tools-on-the-runner-host)
7. [Install & Configure the Self-hosted Runner](#7-install--configure-the-self-hosted-runner)
8. [Configure GitHub Environments & Secrets](#8-configure-github-environments--secrets)
9. [First Deployment](#9-first-deployment)
10. [Access the Application](#10-access-the-application)
11. [Day-2 Operations](#11-day-2-operations)
12. [Troubleshooting](#12-troubleshooting)

---

## 1. Why SSH / Webhooks Are Not Needed

Traditional home-server CI/CD setups require:

| Approach | Problem |
|----------|---------|
| **GitHub SSHes into your server** | Requires an open inbound SSH port. GitHub needs your private key. You trust GitHub's IP ranges. Breaks if your IP changes. |
| **Webhook receiver on your server** | Requires an open inbound HTTP port from the internet. You run a listener process (e.g. webhookd, adnanh/webhook). Still needs inbound connectivity. |
| **Self-hosted runner** (what we use) | The runner initiates an **outbound** WebSocket connection to GitHub. No inbound ports needed. No keys shared with GitHub. The runner polls GitHub for jobs, runs them locally, and reports results back over the same outbound connection. |

### How the self-hosted runner works

```
Home Server (your machine)                    GitHub.com
──────────────────────────                    ──────────
                                                       
  ┌──────────────┐         outbound          ┌──────────────┐
  │ GHA Runner   │──── HTTPS :443 ──────────▶│  GitHub       │
  │ (background   │                           │  Actions      │
  │  service)     │◀─── WebSocket response ───│  service      │
  └──────┬───────┘                           └──────────────┘
         │
         │ local kubectl/helm
         ▼
  ┌──────────────┐
  │ K3s cluster  │
  │ (same host)  │
  └──────────────┘
```

1. Runner starts → opens an outbound HTTPS connection to `pipelines.actions.githubusercontent.com`
2. Runner registers with GitHub: "I'm idle, send me jobs labeled `home-server`"
3. When `deploy-dev.yml` triggers on push to main:
   - `build-and-push` job runs on `ubuntu-latest` (GitHub's cloud)
   - `deploy` job is dispatched to the `home-server` runner
4. Runner receives the job, checks out code, runs `helm upgrade`, reports status
5. Connection stays open — runner never receives inbound traffic

### What you do NOT need

- No SSH port open to the internet
- No webhook listener process
- No GitHub IP allowlist
- No static IP or dynamic DNS (the runner works behind NAT)
- No inbound firewall rules for GitHub
- No SSH keys shared with GitHub
- No `KUBECONFIG` GitHub secret (runner reads `~/.kube/config` locally)

## 2. Self-hosted Runner Network Model

```
 ┌─────────────────────────────────────────────────────────────┐
 │  Internet                                                    │
 │                                                              │
 │  GitHub Actions API (pipelines.actions.githubusercontent.com) │
 │       ▲                                                      │
 │       │ outbound HTTPS :443 (WebSocket)                       │
 │       │                                                      │
 └───────┼──────────────────────────────────────────────────────┘
         │
 ┌───────┼──────────────────────────────────────────────────────┐
 │ Home  │                                                      │
 │ LAN   │  ┌──────────────────────────────────────────────┐    │
 │       │  │           Home Server                         │    │
 │       │  │                                              │    │
 │       │  │  gha-runner user                             │    │
 │       │  │  ├── actions-runner/    ← outbound to GitHub │    │
 │       │  │  ├── .kube/config      ← K3s kubeconfig      │    │
 │       │  │  ├── kubectl, helm     ← local CLI tools     │    │
 │       │  │  └── docker            ← build/tag images    │    │
 │       │  │                                              │    │
 │       │  │  K3s (single-node)                           │    │
 │       │  │  ├── miniurl namespace                       │    │
 │       │  │  │   ├── api-gateway (LoadBalancer)          │    │
 │       │  │  │   ├── eureka-server                       │    │
 │       │  │  │   ├── identity-service                    │    │
 │       │  │  │   ├── url-service                         │    │
 │       │  │  │   ├── redirect-service                    │    │
 │       │  │  │   ├── feature-service                     │    │
 │       │  │  │   ├── notification-service                │    │
 │       │  │  │   └── analytics-service                   │    │
 │       │  │  └── MySQL ×4, Redis, Kafka (Bitnami charts) │    │
 │       │  └──────────────────────────────────────────────┘    │
 │       │                                                      │
 │  Your Laptop ────── http://miniurl.local ────────────────▶   │
 │  (on same LAN)                                               │
 └──────────────────────────────────────────────────────────────┘
```

### Firewall Requirements

| Direction | Port | Protocol | Destination | Purpose |
|-----------|------|----------|-------------|---------|
| **OUTBOUND** | 443 | HTTPS | `*.actions.githubusercontent.com` | Runner connects to GitHub |
| **OUTBOUND** | 443 | HTTPS | `ghcr.io` | Pull container images |
| **OUTBOUND** | 443 | HTTPS | `registry-1.docker.io` | Pull Bitnami Helm charts |
| **OUTBOUND** | 443 | HTTPS | `github.com` | Clone repository |
| **LOCAL** | — | — | K3s API socket | kubectl/helm talk to K3s |
| **INBOUND (LAN)** | 80 | HTTP | server LAN IP | Access the app from your browser |

**Explicitly NOT required:**

| Port | Reason |
|------|--------|
| 22 (SSH) inbound | Replaced by self-hosted runner |
| 443 inbound | GitHub never connects to you |
| Any webhook port | Replaced by self-hosted runner |

## 3. Hardware Requirements

| Resource | Minimum | Recommended |
|----------|---------|-------------|
| CPU | 4 cores | 8 cores |
| RAM | 8 GB | 16 GB |
| Disk | 50 GB | 100 GB SSD |
| Network | Home LAN | Home LAN + static IP |
| OS | Ubuntu 22.04+ | Ubuntu 24.04 LTS |

The full stack (8 microservices + MySQL × 4 + Kafka + Redis) runs at ~6 GB idle.

## 4. OS & Firewall Setup

```bash
# Update system
sudo apt update && sudo apt upgrade -y

# Install essentials
sudo apt install -y curl wget git jq openssl

# Configure firewall — note: NO inbound port 22 from internet
# If using ufw:
sudo ufw allow from 192.168.0.0/16 to any port 22   # SSH from LAN only
sudo ufw allow 80/tcp                                 # HTTP for app access
# Do NOT open 6443 unless cluster access from LAN is needed
sudo ufw enable

# Disable swap (K3s requirement)
sudo swapoff -a
sudo sed -i '/swap/d' /etc/fstab
```

## 5. Install K3s

```bash
curl -sfL https://get.k3s.io | sh -s - server \
  --write-kubeconfig-mode 644

# Verify
sudo kubectl get nodes
# Expected: Ready   control-plane,master

# Set up kubectl for your user
mkdir -p $HOME/.kube
sudo cp /etc/rancher/k3s/k3s.yaml $HOME/.kube/config
sudo chown $(id -u):$(id -g) $HOME/.kube/config
chmod 600 $HOME/.kube/config
kubectl get pods -A
```

## 6. Install Required Tools on the Runner Host

These tools must be present on the home server. The self-hosted runner
calls them directly — no GitHub Actions setup steps are needed.

### 6.1 Tool Inventory

| Tool | Minimum Version | Verify | Required By |
|------|-----------------|--------|-------------|
| `kubectl` | v1.29+ (bundled with K3s) | `kubectl version --client` | All workflows |
| `helm` | v3.14+ | `helm version` | deploy-dev, deploy-prod, rollback |
| `docker` | 24+ | `docker version` | Building images (optional, only if building locally) |
| `jq` | 1.6+ | `jq --version` | JSON parsing in smoke tests |
| `curl` | 7.81+ | `curl --version` | Smoke tests |
| `git` | 2.34+ | `git version` | Checkout code |

### 6.2 Install Helm

```bash
curl https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
helm version
```

### 6.3 Verify all tools

```bash
for tool in kubectl helm jq curl git; do
  if command -v "$tool" &>/dev/null; then
    echo "  $tool: $(command -v "$tool")"
  else
    echo "  MISSING: $tool"
  fi
done
```

## 7. Install & Configure the Self-hosted Runner

### 7.1 Create a dedicated runner user

```bash
sudo useradd -m -s /bin/bash gha-runner
# If building images on the runner host:
sudo usermod -aG docker gha-runner
```

### 7.2 Give runner access to K3s

**This is what replaces the `KUBECONFIG` GitHub secret.** The runner reads
kubeconfig from its home directory, like any local user.

```bash
sudo -u gha-runner mkdir -p /home/gha-runner/.kube
sudo cp /etc/rancher/k3s/k3s.yaml /home/gha-runner/.kube/config
sudo chown gha-runner:gha-runner /home/gha-runner/.kube/config
sudo chmod 600 /home/gha-runner/.kube/config

# Verify the runner user can access the cluster
sudo -u gha-runner kubectl get nodes
```

### 7.3 Register the runner

In GitHub: **Repo → Settings → Actions → Runners → New self-hosted runner**
(choose Linux, x64)

On the server:

```bash
sudo -u gha-runner -i
mkdir -p ~/actions-runner && cd ~/actions-runner

# Download (URL from GitHub UI)
curl -o actions-runner-linux-x64.tar.gz -L \
  https://github.com/actions/runner/releases/download/v2.319.0/actions-runner-linux-x64-2.319.0.tar.gz
tar xzf actions-runner-linux-x64.tar.gz

# Configure — use the token from GitHub's UI
./config.sh \
  --url https://github.com/<OWNER>/miniurl-api \
  --token <RUNNER_TOKEN> \
  --name home-server \
  --labels self-hosted,home-server \
  --unattended
```

> **The `--labels` flag is critical.** Our workflows use `runs-on: [self-hosted, home-server]`.
> Both labels must match. If you use a different label, update the workflows.

### 7.4 Run the runner as a system service

```bash
sudo ./svc.sh install gha-runner
sudo ./svc.sh start
sudo ./svc.sh status
```

The runner will now auto-start on boot and reconnect after network interruptions.

Check in GitHub: **Settings → Actions → Runners** → `home-server` shows **Idle**.

## 8. Configure GitHub Environments & Secrets

### 8.1 Create Environments

In GitHub: **Repo → Settings → Environments**

| Environment | Required Reviewers | Purpose |
|-------------|-------------------|---------|
| `development` | None | Auto-deploy on merge to main |
| `production` | 1+ reviewers | Prod deployment (canary promote or direct) |
| `production-canary-10` | 1 reviewer | Canary phase 1 (10%) |
| `production-canary-25` | 1 reviewer | Canary phase 2 (25%) |
| `production-canary-50` | 1 reviewer | Canary phase 3 (50%) |

### 8.2 Add Secrets to Each Environment

| Secret Name | Example | Notes |
|-------------|---------|-------|
| `DB_ROOT_PASSWORD` | `supersecret123` | MySQL root password for all 4 DB instances |
| `JWT_SECRET` | output of `openssl rand -base64 64` | RS256 signing key |
| `SMTP_HOST` | `smtp.gmail.com` | For notification-service email dispatch |
| `SMTP_PORT` | `587` | |
| `SMTP_USERNAME` | `your-email@gmail.com` | |
| `SMTP_PASSWORD` | your app password | |

> **No `KUBECONFIG` secret is needed.** The runner reads `~/.kube/config` directly
> from the home server. This is the entire point of the self-hosted runner model.

### 8.3 Workflow permissions

**Repo → Settings → Actions → General → Workflow permissions:**
- "Read and write permissions"
- Enable "Allow GitHub Actions to create and approve pull requests"

## 9. First Deployment

### 9.1 Bootstrap the environment

**Actions → Bootstrap Environment → Run workflow:**
- Environment: `dev`
- Install infrastructure: `true`

This creates:
1. `miniurl` namespace
2. MySQL × 4, Redis, Kafka (via Bitnami Helm charts)
3. Kubernetes Secrets (`db-secrets`, `jwt-rsa-keys`, `smtp-credentials`)
4. `ghcr-pull-secret` image pull secret
5. Helm release for MiniURL

Wait ~15 minutes for infrastructure to provision and images to pull.

### 9.2 Deploy the application

After bootstrap, push to `main` (or run deploy-dev manually):

```bash
git push origin main
```

`deploy-dev.yml` triggers automatically:
1. **GitHub** (`ubuntu-latest`): Builds Docker images, pushes to `ghcr.io` with `sha-{hash}` tag
2. **Home server** (`self-hosted, home-server`): Pulls images, runs `helm upgrade --install --atomic`

### 9.3 Production deployment

**Actions → Deploy to Production (Canary) → Run workflow:**
- Image tag: `sha-abc12345` (promote a validated dev tag)
- Skip canary: `false`

Phases (each requires approval):
1. Canary 10% → 60s stabilization → reviewer approves
2. Canary 25% → 60s stabilization → reviewer approves
3. Canary 50% → 60s stabilization → reviewer approves
4. Promote 100%, clean up canary resources

## 10. Access the Application

### Same machine

```bash
GW_IP=$(kubectl -n miniurl get svc api-gateway -o jsonpath='{.spec.clusterIP}')
curl "http://${GW_IP}/actuator/health"
```

### From your LAN

Add to `/etc/hosts` on each client:

```
192.168.1.100  miniurl.local
```

```bash
curl http://miniurl.local/api/health
curl -X POST http://miniurl.local/api/auth/signup \
  -H "Content-Type: application/json" \
  -d '{"username":"demo","email":"demo@test.com","password":"Demo1234!"}'
```

### From the internet (optional)

1. Port-forward 80 on your router → server LAN IP
2. Get a domain, set DNS A record
3. Update `values-home.yaml` ingress host
4. Enable TLS via cert-manager + Let's Encrypt

## 11. Day-2 Operations

### Rollback

Via UI: **Actions → Rollback Deployment → Run workflow**

Via CLI:
```bash
helm history miniurl -n miniurl
helm rollback miniurl -n miniurl --wait
```

### Check logs

```bash
kubectl -n miniurl logs -f deployment/api-gateway --tail=50
```

### Restart a service

```bash
kubectl -n miniurl rollout restart deployment/url-service
```

### Tear down

```bash
helm uninstall miniurl -n miniurl
for db in url identity feature analytics; do helm uninstall "mysql-${db}" -n miniurl; done
helm uninstall redis -n miniurl
helm uninstall kafka -n miniurl
kubectl delete namespace miniurl
sudo /usr/local/bin/k3s-uninstall.sh
```

## 12. Troubleshooting

### Runner is offline

```bash
sudo ./svc.sh status
sudo ./svc.sh restart
journalctl -u actions.runner.* -f
```

Common causes: GitHub token expired, network down, runner config corrupted.
Re-register with a new token if necessary.

### Image pull fails (unauthorized)

The `ghcr-pull-secret` is created fresh on every deploy. If images are from
a private repository, ensure the `GITHUB_TOKEN` has `read:packages` scope.

```bash
kubectl -n miniurl describe pod <pod-name> | grep -A5 "Failed.*image"
```

### Workflow picks wrong runner

Our workflows use `runs-on: [self-hosted, home-server]`. If you have other
self-hosted runners, ensure the `home-server` label is unique to your K3s host.
Check in GitHub: **Settings → Actions → Runners** → click the runner → verify labels.

### Preflight fails

The standardized preflight step checks:
- `kubectl version --client`
- `helm version --short`
- `kubectl config current-context`
- `kubectl cluster-info`
- `kubectl get nodes`

If any fail, the job stops before deploying. Fix the failing tool/access issue
on the server, then re-run.

### Pod stays Pending

```bash
kubectl describe pod -n miniurl <pod-name>
# Check: Events section for "insufficient cpu/memory" or "failed to pull image"
```

Reduce resource requests in `values-home.yaml` if the server is under-provisioned.
