# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

```bash
# Full project build (skip tests)
mvn clean install -DskipTests

# Run all tests
mvn clean test -DskipITs

# Run tests for a single module
mvn test -pl url-service -am

# Start a service locally
mvn spring-boot:run -pl identity-service

# Start infrastructure (Kafka, MySQL, Redis)
docker compose up -d
```

CI uses JDK 21 (Temurin), runs `mvn clean test -DskipITs` on PRs targeting main.

## Architecture

Java 17 / Spring Boot 3.2.0 / Spring Cloud 2023.0.0 multi-module Maven project — a URL shortener migrated from a monolith to microservices.

### Service Map

| Service | Port | Responsibility |
|---------|------|---------------|
| **eureka-server** | 8761 | Service discovery |
| **api-gateway** | 8080 | Routing, RS256 JWT validation (JWKS), Redis rate limiting |
| **identity-service** | 8081 | Auth, user management, RSA key pairs, JWKS endpoint |
| **url-service** | 8082 | URL CRUD, Snowflake ID generation |
| **redirect-service** | 8083 | Reactive (WebFlux) redirect hot-path, Redis-first resolution |
| **feature-service** | 8084 | Feature flags with Redis caching |
| **notification-service** | 8085 | Kafka consumer — email dispatch |
| **analytics-service** | 8086 | Kafka consumer — click event persistence |

Startup order: eureka-server → identity/url/feature → gateway/redirect → notification/analytics.

### Key Patterns

- **RS256 JWTs**: Identity Service signs with private key, Gateway validates via JWKS endpoint
- **Event-driven**: Kafka for async operations (notifications, analytics)
- **Outbox Pattern**: Identity and URL services for atomic DB updates + guaranteed event delivery
- **Database per service**: Separate MySQL instances per service (all defined in docker-compose)
- **Docker build**: Multi-stage `Dockerfile` targeting specific services via `--target`

### Shared Module

`common/` contains shared DTOs (`ApiResponse`, `LoginResponse`, etc.), entities, enums, exceptions, and utilities consumed by all services.

## Project Structure

```
├── common/                  # Shared DTOs, entities, exceptions, utils
├── api-gateway/             # Spring Cloud Gateway
├── eureka-server/           # Netflix Eureka
├── identity-service/        # Auth, JWKS, users
├── url-service/             # URL CRUD, Snowflake IDs
├── redirect-service/        # Reactive redirect (WebFlux)
├── feature-service/         # Feature flags
├── notification-service/    # Kafka -> email
├── analytics-service/       # Kafka -> analytics persistence
├── k8s/                     # K8s manifests
├── helm/                    # Helm charts
├── terraform/               # Infrastructure as code
├── docs/                    # Docs and specs
├── pom.xml                  # Parent POM (all modules listed)
└── Dockerfile               # Multi-stage build for all services
```

## Test Patterns

- Microservice tests use mocked dependencies where possible
- Test naming convention: `{method}_{scenario}_should{ExpectedBehavior}`
