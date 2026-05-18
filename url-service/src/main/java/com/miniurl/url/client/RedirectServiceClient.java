package com.miniurl.url.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Feign client for redirect-service internal endpoints.
 * Used for cache invalidation when URLs are deleted.
 */
@FeignClient(name = "redirect-service")
public interface RedirectServiceClient {

    /**
     * Invalidate the redirect cache for a specific short code.
     * Called after URL deletion to prevent serving stale redirects.
     */
    @DeleteMapping("/internal/cache/{shortCode}")
    void invalidateCache(@PathVariable String shortCode);
}
