package com.miniurl.redirect;

import com.miniurl.common.dto.ClickEvent;
import com.miniurl.redirect.producer.ClickEventProducer;
import com.miniurl.redirect.service.RedirectService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RedirectService Tests")
class RedirectServiceTest {

    @Mock
    private ReactiveRedisTemplate<String, String> redisTemplate;

    @Mock
    private ReactiveValueOperations<String, String> valueOps;

    @Mock
    private WebClient webClient;

    @Mock
    private ClickEventProducer clickEventProducer;

    private MeterRegistry meterRegistry;
    private RedirectService redirectService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        redirectService = new RedirectService(redisTemplate, webClient, clickEventProducer, meterRegistry);
    }

    @Test
    @DisplayName("resolveUrl returns cached URL on cache hit")
    void resolveUrlCacheHit() {
        when(valueOps.get("url:cache:abc123")).thenReturn(Mono.just("https://example.com"));

        StepVerifier.create(redirectService.resolveUrl("abc123"))
                .expectNext("https://example.com")
                .verifyComplete();
    }

    @Test
    @DisplayName("resolveUrl returns empty when cache miss and URL service unavailable")
    void resolveUrlCacheMissNoFallback() {
        when(valueOps.get("url:cache:abc123")).thenReturn(Mono.empty());

        WebClient.RequestHeadersUriSpec uriSpec = mock(WebClient.RequestHeadersUriSpec.class);
        WebClient.RequestHeadersSpec headersSpec = mock(WebClient.RequestHeadersSpec.class);
        WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);

        when(webClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri("/internal/urls/resolve/{code}", "abc123")).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.error(new RuntimeException("URL service down")));

        StepVerifier.create(redirectService.resolveUrl("abc123"))
                .verifyComplete();
    }

    @Test
    @DisplayName("resolveUrl uses different cache keys for different codes")
    void resolveUrlDifferentCacheKeys() {
        when(valueOps.get("url:cache:code1")).thenReturn(Mono.just("https://example1.com"));
        when(valueOps.get("url:cache:code2")).thenReturn(Mono.just("https://example2.com"));

        StepVerifier.create(redirectService.resolveUrl("code1"))
                .expectNext("https://example1.com")
                .verifyComplete();

        StepVerifier.create(redirectService.resolveUrl("code2"))
                .expectNext("https://example2.com")
                .verifyComplete();

        verify(valueOps, times(1)).get("url:cache:code1");
        verify(valueOps, times(1)).get("url:cache:code2");
    }

    @Test
    @DisplayName("publishClickEvent delegates to ClickEventProducer")
    void publishClickEvent() {
        ClickEvent event = ClickEvent.builder()
                .shortCode("abc123")
                .originalUrl("https://example.com")
                .ipAddress("192.168.1.1")
                .userAgent("Mozilla/5.0")
                .referer("https://referrer.com")
                .timestamp(LocalDateTime.now())
                .userId(42L)
                .build();

        when(clickEventProducer.sendClickEvent(event)).thenReturn(Mono.empty().then());

        StepVerifier.create(redirectService.publishClickEvent(event))
                .verifyComplete();

        verify(clickEventProducer, times(1)).sendClickEvent(event);
    }

    @Test
    @DisplayName("publishClickEvent propagates producer error")
    void publishClickEventError() {
        ClickEvent event = ClickEvent.builder()
                .shortCode("abc123")
                .originalUrl("https://example.com")
                .build();

        when(clickEventProducer.sendClickEvent(event))
                .thenReturn(Mono.error(new RuntimeException("Kafka unavailable")));

        StepVerifier.create(redirectService.publishClickEvent(event))
                .expectError(RuntimeException.class)
                .verify();
    }

    @Test
    @DisplayName("resolveUrl caches URL from fallback service on cache miss")
    void resolveUrlCacheMissWithFallback() {
        when(valueOps.get("url:cache:xyz789")).thenReturn(Mono.empty());
        when(valueOps.set("url:cache:xyz789", "https://fallback.com", java.time.Duration.ofHours(1)))
                .thenReturn(Mono.just(true));

        WebClient.RequestHeadersUriSpec uriSpec = mock(WebClient.RequestHeadersUriSpec.class);
        WebClient.RequestHeadersSpec headersSpec = mock(WebClient.RequestHeadersSpec.class);
        WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);

        when(webClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri("/internal/urls/resolve/{code}", "xyz789")).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just("https://fallback.com"));

        StepVerifier.create(redirectService.resolveUrl("xyz789"))
                .expectNext("https://fallback.com")
                .verifyComplete();

        verify(valueOps, times(1)).get("url:cache:xyz789");
        verify(valueOps, times(1)).set("url:cache:xyz789", "https://fallback.com", java.time.Duration.ofHours(1));
    }

    @Test
    @DisplayName("resolveUrl calls opsForValue once per resolution")
    void resolveUrlUsesOpsForValue() {
        when(valueOps.get("url:cache:test")).thenReturn(Mono.just("https://test.com"));

        StepVerifier.create(redirectService.resolveUrl("test"))
                .expectNext("https://test.com")
                .verifyComplete();

        verify(redisTemplate, times(1)).opsForValue();
    }

    // -----------------------------------------------------------------------
    // P0-1 Fix: Redis failure scenarios
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("resolveUrl falls back to URL service when Redis get() errors")
    void resolveUrlRedisGetErrorFallsBackToUrlService() {
        when(valueOps.get("url:cache:err1"))
                .thenReturn(Mono.error(new RuntimeException("Redis connection refused")));
        when(valueOps.set(eq("url:cache:err1"), eq("https://from-url-service.com"), any()))
                .thenReturn(Mono.just(true));

        WebClient.RequestHeadersUriSpec uriSpec = mock(WebClient.RequestHeadersUriSpec.class);
        WebClient.RequestHeadersSpec headersSpec = mock(WebClient.RequestHeadersSpec.class);
        WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);

        when(webClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri("/internal/urls/resolve/{code}", "err1")).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just("https://from-url-service.com"));

        StepVerifier.create(redirectService.resolveUrl("err1"))
                .expectNext("https://from-url-service.com")
                .verifyComplete();

        // Verify URL service was called as fallback
        verify(webClient).get();
        // Verify cache was populated after fallback
        verify(valueOps).set(eq("url:cache:err1"), eq("https://from-url-service.com"), any());
    }

    @Test
    @DisplayName("resolveUrl returns URL even when Redis set() fails after URL service fallback")
    void resolveUrlRedisSetErrorStillReturnsUrl() {
        when(valueOps.get("url:cache:err2"))
                .thenReturn(Mono.error(new RuntimeException("Redis connection refused")));
        when(valueOps.set(eq("url:cache:err2"), eq("https://resolved-url.com"), any()))
                .thenReturn(Mono.error(new RuntimeException("Redis write failed")));

        WebClient.RequestHeadersUriSpec uriSpec = mock(WebClient.RequestHeadersUriSpec.class);
        WebClient.RequestHeadersSpec headersSpec = mock(WebClient.RequestHeadersSpec.class);
        WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);

        when(webClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri("/internal/urls/resolve/{code}", "err2")).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just("https://resolved-url.com"));

        // Should still return the URL even though caching failed
        StepVerifier.create(redirectService.resolveUrl("err2"))
                .expectNext("https://resolved-url.com")
                .verifyComplete();
    }

    @Test
    @DisplayName("resolveUrl returns empty when Redis errors AND URL service returns empty")
    void resolveUrlRedisErrorAndUrlServiceEmpty() {
        when(valueOps.get("url:cache:err3"))
                .thenReturn(Mono.error(new RuntimeException("Redis connection refused")));

        WebClient.RequestHeadersUriSpec uriSpec = mock(WebClient.RequestHeadersUriSpec.class);
        WebClient.RequestHeadersSpec headersSpec = mock(WebClient.RequestHeadersSpec.class);
        WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);

        when(webClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri("/internal/urls/resolve/{code}", "err3")).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.empty());

        StepVerifier.create(redirectService.resolveUrl("err3"))
                .verifyComplete();
    }

    @Test
    @DisplayName("resolveUrl returns empty when Redis errors AND URL service errors")
    void resolveUrlRedisErrorAndUrlServiceError() {
        when(valueOps.get("url:cache:err4"))
                .thenReturn(Mono.error(new RuntimeException("Redis connection refused")));

        WebClient.RequestHeadersUriSpec uriSpec = mock(WebClient.RequestHeadersUriSpec.class);
        WebClient.RequestHeadersSpec headersSpec = mock(WebClient.RequestHeadersSpec.class);
        WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);

        when(webClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri("/internal/urls/resolve/{code}", "err4")).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.error(new RuntimeException("URL service down")));

        StepVerifier.create(redirectService.resolveUrl("err4"))
                .verifyComplete();
    }

    @Test
    @DisplayName("resolveUrl cache hit still works when Redis is healthy (regression check)")
    void resolveUrlCacheHitStillWorks() {
        when(valueOps.get("url:cache:healthy")).thenReturn(Mono.just("https://cached-url.com"));

        StepVerifier.create(redirectService.resolveUrl("healthy"))
                .expectNext("https://cached-url.com")
                .verifyComplete();

        // URL service should NOT be called on cache hit
        verify(webClient, never()).get();
    }

    @Test
    @DisplayName("resolveUrl cache miss with Redis set error still returns URL (regression check)")
    void resolveUrlCacheMissSetErrorStillReturnsUrl() {
        when(valueOps.get("url:cache:miss1")).thenReturn(Mono.empty());
        when(valueOps.set(eq("url:cache:miss1"), eq("https://miss-url.com"), any()))
                .thenReturn(Mono.error(new RuntimeException("Redis write failed")));

        WebClient.RequestHeadersUriSpec uriSpec = mock(WebClient.RequestHeadersUriSpec.class);
        WebClient.RequestHeadersSpec headersSpec = mock(WebClient.RequestHeadersSpec.class);
        WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);

        when(webClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri("/internal/urls/resolve/{code}", "miss1")).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just("https://miss-url.com"));

        StepVerifier.create(redirectService.resolveUrl("miss1"))
                .expectNext("https://miss-url.com")
                .verifyComplete();
    }

    // -----------------------------------------------------------------------
    // Custom Metric: redirect_redis_fallback_total
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("redirect_redis_fallback_total counter increments on Redis get() error")
    void redisFallbackCounterIncrementsOnRedisGetError() {
        when(valueOps.get("url:cache:fallback1"))
                .thenReturn(Mono.error(new RuntimeException("Redis connection refused")));
        when(valueOps.set(eq("url:cache:fallback1"), eq("https://from-url-service.com"), any()))
                .thenReturn(Mono.just(true));

        WebClient.RequestHeadersUriSpec uriSpec = mock(WebClient.RequestHeadersUriSpec.class);
        WebClient.RequestHeadersSpec headersSpec = mock(WebClient.RequestHeadersSpec.class);
        WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);

        when(webClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri("/internal/urls/resolve/{code}", "fallback1")).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just("https://from-url-service.com"));

        StepVerifier.create(redirectService.resolveUrl("fallback1"))
                .expectNext("https://from-url-service.com")
                .verifyComplete();

        double count = meterRegistry.get("redirect_redis_fallback_total")
                .tag("reason", "error")
                .counter()
                .count();
        assertEquals(1.0, count, "Counter should increment once on Redis get() error");
    }

    @Test
    @DisplayName("redirect_redis_fallback_total counter does NOT increment on cache hit")
    void redisFallbackCounterDoesNotIncrementOnCacheHit() {
        when(valueOps.get("url:cache:healthy2")).thenReturn(Mono.just("https://cached-url.com"));

        StepVerifier.create(redirectService.resolveUrl("healthy2"))
                .expectNext("https://cached-url.com")
                .verifyComplete();

        double count = meterRegistry.get("redirect_redis_fallback_total")
                .tag("reason", "error")
                .counter()
                .count();
        assertEquals(0.0, count, "Counter should NOT increment on cache hit");
    }

    @Test
    @DisplayName("redirect_redis_fallback_total counter does NOT increment on cache miss (no Redis error)")
    void redisFallbackCounterDoesNotIncrementOnCacheMiss() {
        when(valueOps.get("url:cache:miss2")).thenReturn(Mono.empty());
        when(valueOps.set(eq("url:cache:miss2"), eq("https://miss-url.com"), any()))
                .thenReturn(Mono.just(true));

        WebClient.RequestHeadersUriSpec uriSpec = mock(WebClient.RequestHeadersUriSpec.class);
        WebClient.RequestHeadersSpec headersSpec = mock(WebClient.RequestHeadersSpec.class);
        WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);

        when(webClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri("/internal/urls/resolve/{code}", "miss2")).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just("https://miss-url.com"));

        StepVerifier.create(redirectService.resolveUrl("miss2"))
                .expectNext("https://miss-url.com")
                .verifyComplete();

        double count = meterRegistry.get("redirect_redis_fallback_total")
                .tag("reason", "error")
                .counter()
                .count();
        assertEquals(0.0, count, "Counter should NOT increment on normal cache miss (no Redis error)");
    }
}
