# MyURL Microservices - Kubernetes Testing and Integration Strategy

## Overview

This document provides a detailed testing strategy for the MyURL microservices project, covering unit testing, integration testing, and end-to-end testing.

---

## 1. Unit Testing Strategy

### 1.1 Testing Framework
- **JUnit 5** for test framework
- **Mockito** for mocking dependencies
- **AssertJ** for fluent assertions

### 1.2 Coverage Requirements
| Service | Minimum Coverage | Focus Areas |
|---------|-----------------|-------------|
| Identity Service | 85% | AuthService, JwtService, TokenService |
| URL Service | 80% | UrlService, UrlUsageLimitService |
| Redirect Service | 85% | RedirectService (core logic) |
| Feature Service | 80% | FeatureFlagService, GlobalFlagService |
| Notification Service | 75% | EmailService (business logic) |
| Analytics Service | 75% | AnalyticsConsumer (business logic) |

### 1.3 Test Categories

#### Identity Service Tests
- **AuthService Tests**
  - User registration with valid data
  - User registration with invalid password
  - OTP generation and verification
  - Password reset flow
  - Username/email uniqueness checks
  - Rate limiting (signup/password reset)
  
- **JwtService Tests**
  - JWT token generation (RS256)
  - Token expiration validation
  - Token verification
  
- **TokenService Tests**
  - Token generation (email, password reset)
  - Token validation
  - Token invalidation

#### URL Service Tests
- **UrlService Tests**
  - URL creation with valid data
  - URL creation with invalid URL
  - URL alias generation
  - URL lookup
  - User URL listing
  - Rate limit checking
  
- **UrlUsageLimitService Tests**
  - Minute limit enforcement (10/min)
  - Daily limit enforcement (100/day)
  - Monthly limit enforcement (1000/month)
  - Usage stats calculation
  
- **UrlCreationMinuteTracker Tests**
  - Minute counter increment
  - Counter cleanup

#### Redirect Service Tests
- **RedirectService Tests**
  - URL resolution from cache (Redis)
  - URL resolution from URL service (fallback)
  - Click event publishing
  - Cache TTL handling

#### Feature Service Tests
- **FeatureFlagService Tests**
  - Feature flag retrieval from cache
  - Feature flag toggle
  - Feature flag invalidation
  
- **GlobalFlagService Tests**
  - Global flag retrieval
  - Global flag toggle

#### Notification Service Tests
- **EmailService Tests**
  - Email template processing
  - Email sending (with mocked JavaMailSender)
  - SMTP configuration checks

#### Analytics Service Tests
- **AnalyticsConsumer Tests**
  - Click event processing
  - Event persistence

---

## 2. Integration Testing Strategy

### 2.1 Testing Framework
- **Spring Boot Test** for integration context
- **Testcontainers** for real infrastructure
- **TestContainers JUnit 5 Extension**

### 2.2 Integration Test Profile
```yaml
# src/test/resources/application-integration-test.yml
spring:
  profiles: integration-test
  datasource:
    url: jdbc:tc:mysql:8.0:///integration_test
    username: root
    password: root
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS}
  redis:
    host: ${REDIS_HOST}
    port: ${REDIS_PORT}
```

### 2.3 Testcontainers Setup

#### Database Containers
```java
@Testcontainers
@SpringBootTest(webEnvironment = RANDOM_PORT)
@ActiveProfiles("integration-test")
class AuthServiceIntegrationTest {

    @Container
    static MySQLContainer<?> mysqlIdentity = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("integration_test")
        .withUsername("root")
        .withPassword("root");

    @Container
    static MySQLContainer<?> mysqlUrl = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("integration_url")
        .withUsername("root")
        .withPassword("root");
}
```

#### Kafka Container
```java
@Container
static KafkaContainer kafka = new KafkaContainer("confluentinc/cp-kafka:latest");
```

#### Redis Container
```java
@Container
static RedisContainer redis = new RedisContainer("redis:7.0-alpine");
```

### 2.4 Service-Specific Integration Tests

#### Identity Service Integration Tests
1. **User Registration Flow**
   - Register user -> Verify DB entry -> Verify email sent
2. **Password Reset Flow**
   - Request reset -> Verify token in DB -> Reset password
3. **OTP Verification**
   - Generate OTP -> Verify in DB -> Verify OTP
4. **Email Invite Flow**
   - Create invite -> Send email event -> Accept invite

#### URL Service Integration Tests
1. **URL Creation Flow**
   - Create URL -> Verify DB entry -> Verify Kafka event
2. **Rate Limiting**
   - Exceed minute limit -> Verify exception
   - Exceed daily limit -> Verify exception
   - Exceed monthly limit -> Verify exception
3. **URL Deletion Flow**
   - Delete URL -> Verify DB removal -> Verify Kafka event

#### Redirect Service Integration Tests
1. **Cache Hit Flow**
   - Set cache -> Resolve URL -> Verify cache hit
2. **Cache Miss Flow**
   - Clear cache -> Resolve URL -> Verify DB lookup
3. **Click Event Flow**
   - Publish event -> Verify Kafka topic

#### Feature Service Integration Tests
1. **Feature Flag Cache**
   - Set flag -> Verify cache -> Update flag -> Verify cache invalidation

#### Notification Service Integration Tests
1. **Email Event Processing**
   - Send Kafka event -> Verify email sent
2. **Template Processing**
   - Verify Thymeleaf template rendering

#### Analytics Service Integration Tests
1. **Click Event Processing**
   - Send Kafka event -> Verify DB entry

---

## 3. End-to-End Testing Strategy

### 3.1 Testing Framework
- **Testcontainers** for full environment
- **RestAssured** for API testing
- **JUnit 5** for test framework

### 3.2 E2E Test Scenarios

#### Scenario 1: User Registration Flow
```java
@Test
void userRegistrationFlow() {
    // 1. Create email invite
    // 2. Accept invite
    // 3. Complete registration
    // 4. Verify email is sent
    // 5. Verify user exists in DB
}
```

#### Scenario 2: URL Shortening Flow
```java
@Test
void urlShorteningFlow() {
    // 1. Login as user
    // 2. Create URL
    // 3. Verify URL in DB
    // 4. Verify Kafka event published
}
```

#### Scenario 3: URL Redirect Flow
```java
@Test
void urlRedirectFlow() {
    // 1. Create short URL
    // 2. Access redirect endpoint
    // 3. Verify redirect
    // 4. Verify click event in Kafka
}
```

#### Scenario 4: Feature Flag Flow
```java
@Test
void featureFlagFlow() {
    // 1. Login as admin
    // 2. Create feature flag
    // 3. Verify feature flag in DB
    // 4. Toggle flag
    // 5. Verify cache invalidation
}
```

---

## 4. Test Profiles

### 4.1 Profile Definitions

| Profile | Description | Dependencies |
|---------|-------------|--------------|
| `test` | Unit tests only | Mocked dependencies |
| `integration-test` | Integration tests | Testcontainers (MySQL, Kafka, Redis) |
| `e2e-test` | End-to-end tests | Full container stack |

### 4.2 Profile Configuration

```yaml
# src/test/resources/application-test.yml
spring:
  profiles: test
  datasource:
    url: jdbc:h2:mem:testdb
  kafka:
    bootstrap-servers: dummy:9092
  redis:
    host: dummy
```

```yaml
# src/test/resources/application-integration-test.yml
spring:
  profiles: integration-test
  datasource:
    url: jdbc:tc:mysql:8.0:///testdb
    username: root
    password: root
  kafka:
    bootstrap-servers: localhost:9092
  redis:
    host: localhost
```

```yaml
# src/test/resources/application-e2e-test.yml
spring:
  profiles: e2e-test
  datasource:
    url: jdbc:mysql://localhost:3306/e2e_test
    username: root
    password: root
  kafka:
    bootstrap-servers: localhost:9092
  redis:
    host: localhost
```

---

## 5. Test Execution

### 5.1 Unit Tests
```bash
mvn test -DskipITs
```

### 5.2 Integration Tests
```bash
mvn verify -Pintegration-test
```

### 5.3 E2E Tests
```bash
# Start Testcontainers environment
docker-compose -f docker-compose.test.yml up -d

# Run E2E tests
mvn verify -Pe2e-test

# Cleanup
docker-compose -f docker-compose.test.yml down
```

---

## 6. Coverage Reporting

### 6.1 JaCoCo Configuration
```xml
<plugin>
    <groupId>org.jacoco</groupId>
    <artifactId>jacoco-maven-plugin</artifactId>
    <version>0.8.12</version>
    <executions>
        <execution>
            <goals>
                <goal>prepare-agent</goal>
            </goals>
        </execution>
        <execution>
            <id>report</id>
            <phase>verify</phase>
            <goals>
                <goal>report</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

### 6.2 Coverage Report
```bash
mvn clean verify
open target/site/jacoco/index.html
```

---

## 7. CI/CD Integration

### 7.1 GitHub Actions Workflow
```yaml
- name: Run Unit Tests
  run: mvn test -DskipITs

- name: Run Integration Tests
  run: mvn verify -Pintegration-test

- name: Upload Coverage Report
  uses: actions/upload-artifact@v4
  with:
    name: coverage-report
    path: target/site/jacoco/
```

### 7.2 Coverage Threshold
```xml
<plugin>
    <groupId>org.jacoco</groupId>
    <artifactId>jacoco-maven-plugin</artifactId>
    <executions>
        <execution>
            <id>check</id>
            <goals>
                <goal>check</goal>
            </goals>
            <configuration>
                <rules>
                    <rule>
                        <element>BUNDLE</element>
                        <limits>
                            <limit>
                                <counter>LINE</counter>
                                <value>COVEREDRATIO</value>
                                <minimum>0.80</minimum>
                            </limit>
                        </limits>
                    </rule>
                </rules>
            </configuration>
        </execution>
    </executions>
</plugin>