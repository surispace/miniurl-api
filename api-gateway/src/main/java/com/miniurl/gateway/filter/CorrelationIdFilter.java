package com.miniurl.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Global filter that ensures every request has a correlation ID for distributed tracing.
 * If the client sends an X-Correlation-ID header, it is propagated as-is.
 * Otherwise, a new UUID-based correlation ID is generated.
 * The correlation ID is added to the response header and MDC context for logging.
 */
@Component
public class CorrelationIdFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);
    public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    public static final String CORRELATION_ID_MDC_KEY = "correlationId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String correlationId = exchange.getRequest().getHeaders().getFirst(CORRELATION_ID_HEADER);

        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        // Add correlation ID to MDC for logging
        MDC.put(CORRELATION_ID_MDC_KEY, correlationId);

        // Mutate the request to include the correlation ID header for downstream services
        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header(CORRELATION_ID_HEADER, correlationId)
                .build();

        ServerWebExchange mutatedExchange = exchange.mutate()
                .request(mutatedRequest)
                .build();

        // Add correlation ID to response headers
        mutatedExchange.getResponse().getHeaders().add(CORRELATION_ID_HEADER, correlationId);

        return chain.filter(mutatedExchange)
                .doFinally(signalType -> MDC.remove(CORRELATION_ID_MDC_KEY));
    }

    @Override
    public int getOrder() {
        // Run early in the filter chain, before authentication
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
