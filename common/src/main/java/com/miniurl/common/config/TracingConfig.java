package com.miniurl.common.config;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Distributed tracing configuration for all microservices.
 * Configures Micrometer Observation with OpenTelemetry bridge for trace propagation
 * and MDC (Mapped Diagnostic Context) integration for structured logging.
 *
 * The CorrelationIdFilter in the API Gateway sets X-Correlation-ID on every
 * request. This config ensures observation scope is available for all services
 * and that trace context is included in log output via MDC.
 */
@Configuration
public class TracingConfig {

    /**
     * Provides the ObservationRegistry bean for all services.
     * Micrometer Tracing auto-configures this when micrometer-tracing-bridge-otel
     * is on the classpath, but explicit declaration ensures it's available
     * in the common module context.
     */
    @Bean
    public ObservationRegistry observationRegistry() {
        return ObservationRegistry.create();
    }

    /**
     * Enables {@code @Observed} annotation support for declarative observation
     * on methods. Services can annotate methods with @Observed to automatically
     * create spans for tracing.
     */
    @Bean
    public ObservedAspect observedAspect(ObservationRegistry observationRegistry) {
        return new ObservedAspect(observationRegistry);
    }
}
