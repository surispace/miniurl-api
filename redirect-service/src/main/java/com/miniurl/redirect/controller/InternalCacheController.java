package com.miniurl.redirect.controller;

import com.miniurl.redirect.service.RedirectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Internal endpoints for inter-service communication.
 * Not exposed through the API Gateway.
 */
@Slf4j
@RestController
@RequestMapping("/internal/cache")
@RequiredArgsConstructor
public class InternalCacheController {

    private final RedirectService redirectService;

    /**
     * Invalidate the redirect cache for a specific short code.
     * Called by url-service when a URL is deleted.
     */
    @DeleteMapping("/{shortCode}")
    public Mono<ResponseEntity<Void>> invalidateCache(@PathVariable String shortCode) {
        log.info("Cache invalidation requested for short code: {}", shortCode);
        return redirectService.invalidateCache(shortCode)
                .thenReturn(ResponseEntity.ok().build());
    }
}
