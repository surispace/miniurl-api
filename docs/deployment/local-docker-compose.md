# Local Development with Docker Compose

Run the full MiniURL stack locally using Docker Compose — ideal for feature development and debugging without Kubernetes.

## Quick Start

```bash
# Start all infrastructure + services
docker compose up -d

# Check status
docker compose ps

# View logs
docker compose logs -f api-gateway

# Stop everything
docker compose down
```

## What's Included

The [`docker-compose.yml`](../../docker-compose.yml) starts:

| Component | Port(s) | Purpose |
|-----------|---------|---------|
| **api-gateway** | 8080 | Entry point, routes to all services |
| **eureka-server** | 8761 | Service discovery |
| **identity-service** | 8081 | Authentication, JWT |
| **url-service** | 8082 | URL CRUD, short code generation |
| **redirect-service** | 8083 | URL resolution, redirects |
| **feature-service** | 8084 | Feature flags |
| **notification-service** | 8085 | Email notifications |
| **analytics-service** | 8086 | Click analytics |
| **Kafka** | 9092 | Async messaging |
| **Zookeeper** | 2181 | Kafka coordination |
| **Redis** | 6379 | Caching |
| **MySQL (url_db)** | 3307 | URL data |
| **MySQL (identity_db)** | 3308 | User/role data |
| **MySQL (feature_db)** | 3309 | Feature flag data |
| **Prometheus** | 9090 | Metrics collection |
| **Grafana** | 3000 | Dashboards |

## Prerequisites

- Docker Desktop 4.x+ (or Docker Engine + Docker Compose v2)
- At least 8 GB RAM allocated to Docker
- JDK 21 (for building locally)

## Building Before Running

Docker Compose uses pre-built images. Build them first:

```bash
# Build all services
mvn clean package -DskipTests

# Build Docker images
docker compose build
```

Or build a single service:

```bash
docker compose build api-gateway
```

## Useful Commands

```bash
# Rebuild and restart a single service
docker compose up -d --build api-gateway

# Scale a service for load testing
docker compose up -d --scale redirect-service=3

# Run in foreground (see all logs)
docker compose up

# Reset everything (volumes included)
docker compose down -v
```

## Accessing Services

```bash
# Health check
curl http://localhost:8080/api/health

# Create a short URL
curl -X POST http://localhost:8080/api/urls \
  -H "Content-Type: application/json" \
  -d '{"originalUrl": "https://example.com/very-long-url"}'

# Eureka dashboard
open http://localhost:8761

# Grafana (admin/admin)
open http://localhost:3000
```

## Configuration

Environment variables are set in [`docker-compose.yml`](../../docker-compose.yml). Override them with a `.env` file:

```bash
# .env (git-ignored)
SPRING_PROFILES_ACTIVE=local
MYSQL_ROOT_PASSWORD=devpassword
JWT_SECRET=dev-secret-key
```

## Limitations

- **No TLS**: Docker Compose runs HTTP only
- **No HPA**: Autoscaling is a Kubernetes feature
- **No NetworkPolicies**: All containers share the same network
- **No canary deployments**: Use Minikube for testing canary flows
- **Single instance per service**: Scale manually with `--scale`

For Kubernetes-specific features (HPA, NetworkPolicies, canary), use [Local Minikube](local-minikube.md).
