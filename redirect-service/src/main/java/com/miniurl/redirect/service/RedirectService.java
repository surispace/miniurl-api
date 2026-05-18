package com.miniurl.redirect.service;

import com.miniurl.common.dto.ClickEvent;
import com.miniurl.redirect.producer.ClickEventProducer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Slf4j
@Service
public class RedirectService {

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final WebClient webClient;
    private final ClickEventProducer clickEventProducer;
    private final Counter redisFallbackCounter;

    private static final String REDIRECT_CACHE_PREFIX = "url:cache:";
    private static final Duration CACHE_TTL = Duration.ofHours(1);

    public RedirectService(ReactiveRedisTemplate<String, String> redisTemplate,
                           WebClient webClient,
                           ClickEventProducer clickEventProducer,
                           MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.webClient = webClient;
        this.clickEventProducer = clickEventProducer;
        this.redisFallbackCounter = Counter.builder("redirect_redis_fallback_total")
                .description("Number of times Redis fallback to URL service was triggered")
                .tag("reason", "error")
                .register(meterRegistry);
    }

    /**
     * Resolves a short code to an original URL.
     * Strategy: Redis Cache -> URL Service API -> Redis Cache
     */
    public Mono<String> resolveUrl(String code) {
        String cacheKey = REDIRECT_CACHE_PREFIX + code;

        return redisTemplate.opsForValue().get(cacheKey)
            .doOnNext(url -> log.debug("Cache hit for short code: {}", code))
            .onErrorResume(e -> {
                log.warn("Redis unavailable for short code {}: {}. Falling back to URL service.", code, e.getMessage());
                redisFallbackCounter.increment();
                return Mono.empty();
            })
            .switchIfEmpty(Mono.defer(() -> fetchFromUrlService(code)
                .flatMap(url -> redisTemplate.opsForValue()
                    .set(cacheKey, url, CACHE_TTL)
                    .onErrorResume(e -> {
                        log.warn("Failed to cache URL for short code {}: {}", code, e.getMessage());
                        return Mono.empty();
                    })
                    .thenReturn(url))
                .doOnNext(url -> log.debug("Cache miss for short code: {}. Fetched from URL service.", code))
            ));
    }

    private Mono<String> fetchFromUrlService(String code) {
        // Call URL service to get the original URL
        // The URL service has an endpoint that returns the URL entity or a response
        // Based on the monolith, we can use a dedicated internal endpoint or the existing one
        // For this implementation, we assume the URL service provides a simple GET /internal/urls/resolve/{code}
        return webClient.get()
            .uri("/internal/urls/resolve/{code}", code)
            .retrieve()
            .bodyToMono(String.class)
            .onErrorResume(e -> {
                log.error("Error fetching URL from URL service for code {}: {}", code, e.getMessage());
                return Mono.empty();
            });
    }

    public Mono<Void> publishClickEvent(ClickEvent event) {
        return clickEventProducer.sendClickEvent(event);
    }

    /**
     * Invalidate the redirect cache for a specific short code.
     * Called when a URL is deleted to prevent serving stale redirects.
     */
    public Mono<Boolean> invalidateCache(String shortCode) {
        String cacheKey = REDIRECT_CACHE_PREFIX + shortCode;
        return redisTemplate.opsForValue().delete(cacheKey)
                .doOnSuccess(deleted -> {
                    if (Boolean.TRUE.equals(deleted)) {
                        log.info("Cache invalidated for short code: {}", shortCode);
                    } else {
                        log.debug("No cache entry found for short code: {}", shortCode);
                    }
                });
    }
}
