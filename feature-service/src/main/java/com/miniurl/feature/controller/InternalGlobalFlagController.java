package com.miniurl.feature.controller;

import com.miniurl.feature.service.GlobalFlagService;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Internal endpoints for inter-service communication.
 * Not exposed through the API Gateway.
 */
@Hidden
@RestController
@RequestMapping("/internal/global-flags")
@RequiredArgsConstructor
public class InternalGlobalFlagController {

    private final GlobalFlagService globalFlagService;

    /**
     * Check if a specific global feature is enabled.
     * Used by identity-service for self-invite feature gating.
     */
    @GetMapping("/{featureKey}/enabled")
    public ResponseEntity<Map<String, Boolean>> isEnabled(@PathVariable String featureKey) {
        boolean enabled = globalFlagService.isGlobalFeatureEnabled(featureKey);
        return ResponseEntity.ok(Map.of("enabled", enabled));
    }
}
