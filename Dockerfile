# syntax=docker/dockerfile:1
FROM maven:3-eclipse-temurin-21 AS base-build
WORKDIR /app
COPY pom.xml .
# Copy parent pom and all module poms for reactor resolution
COPY common/pom.xml common/
COPY eureka-server/pom.xml eureka-server/
COPY api-gateway/pom.xml api-gateway/
COPY identity-service/pom.xml identity-service/
COPY url-service/pom.xml url-service/
COPY redirect-service/pom.xml redirect-service/
COPY feature-service/pom.xml feature-service/
COPY notification-service/pom.xml notification-service/
COPY analytics-service/pom.xml analytics-service/
# Copy common source code (dependency for all services)
COPY common/src common/src/
RUN --mount=type=cache,target=/root/.m2 mvn dependency:go-offline -DskipTests || true

# Individual service stages
FROM base-build AS build-eureka-server
COPY eureka-server eureka-server/
RUN --mount=type=cache,target=/root/.m2 mvn package -pl eureka-server -DskipTests -am

FROM base-build AS build-api-gateway
COPY api-gateway api-gateway/
RUN --mount=type=cache,target=/root/.m2 mvn package -pl api-gateway -DskipTests -am

FROM base-build AS build-identity-service
COPY identity-service identity-service/
RUN --mount=type=cache,target=/root/.m2 mvn package -pl identity-service -DskipTests -am

FROM base-build AS build-url-service
COPY url-service url-service/
RUN --mount=type=cache,target=/root/.m2 mvn package -pl url-service -DskipTests -am

FROM base-build AS build-redirect-service
COPY redirect-service redirect-service/
RUN --mount=type=cache,target=/root/.m2 mvn package -pl redirect-service -DskipTests -am

FROM base-build AS build-feature-service
COPY feature-service feature-service/
RUN --mount=type=cache,target=/root/.m2 mvn package -pl feature-service -DskipTests -am

FROM base-build AS build-notification-service
COPY notification-service notification-service/
RUN --mount=type=cache,target=/root/.m2 mvn package -pl notification-service -DskipTests -am

FROM base-build AS build-analytics-service
COPY analytics-service analytics-service/
RUN --mount=type=cache,target=/root/.m2 mvn package -pl analytics-service -DskipTests -am

# Runtime stages
FROM eclipse-temurin:21-jre-alpine AS eureka-server
WORKDIR /app
RUN mkdir -p /app/logs && \
    addgroup -S appgroup && \
    adduser -S appuser -G appgroup && \
    chown -R appuser:appgroup /app
COPY --from=build-eureka-server /app/eureka-server/target/*.jar app.jar
USER appuser
EXPOSE 8761
ENTRYPOINT ["java", "-jar", "app.jar"]

FROM eclipse-temurin:21-jre-alpine AS api-gateway
WORKDIR /app
RUN mkdir -p /app/logs && \
    addgroup -S appgroup && \
    adduser -S appuser -G appgroup && \
    chown -R appuser:appgroup /app
COPY --from=build-api-gateway /app/api-gateway/target/*.jar app.jar
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

FROM eclipse-temurin:21-jre-alpine AS identity-service
WORKDIR /app
RUN mkdir -p /app/logs && \
    addgroup -S appgroup && \
    adduser -S appuser -G appgroup && \
    chown -R appuser:appgroup /app
COPY --from=build-identity-service /app/identity-service/target/*.jar app.jar
USER appuser
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]

FROM eclipse-temurin:21-jre-alpine AS url-service
WORKDIR /app
RUN mkdir -p /app/logs && \
    addgroup -S appgroup && \
    adduser -S appuser -G appgroup && \
    chown -R appuser:appgroup /app
COPY --from=build-url-service /app/url-service/target/*.jar app.jar
USER appuser
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]

FROM eclipse-temurin:21-jre-alpine AS redirect-service
WORKDIR /app
RUN mkdir -p /app/logs && \
    addgroup -S appgroup && \
    adduser -S appuser -G appgroup && \
    chown -R appuser:appgroup /app
COPY --from=build-redirect-service /app/redirect-service/target/*.jar app.jar
USER appuser
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]

FROM eclipse-temurin:21-jre-alpine AS feature-service
WORKDIR /app
RUN mkdir -p /app/logs && \
    addgroup -S appgroup && \
    adduser -S appuser -G appgroup && \
    chown -R appuser:appgroup /app
COPY --from=build-feature-service /app/feature-service/target/*.jar app.jar
USER appuser
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "app.jar"]

FROM eclipse-temurin:21-jre-alpine AS notification-service
WORKDIR /app
RUN mkdir -p /app/logs && \
    addgroup -S appgroup && \
    adduser -S appuser -G appgroup && \
    chown -R appuser:appgroup /app
COPY --from=build-notification-service /app/notification-service/target/*.jar app.jar
USER appuser
EXPOSE 8085
ENTRYPOINT ["java", "-jar", "app.jar"]

FROM eclipse-temurin:21-jre-alpine AS analytics-service
WORKDIR /app
RUN mkdir -p /app/logs && \
    addgroup -S appgroup && \
    adduser -S appuser -G appgroup && \
    chown -R appuser:appgroup /app
COPY --from=build-analytics-service /app/analytics-service/target/*.jar app.jar
USER appuser
EXPOSE 8086
ENTRYPOINT ["java", "-jar", "app.jar"]
