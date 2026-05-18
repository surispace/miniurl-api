package com.miniurl.feature.controller;

import com.miniurl.dto.FeatureFlagDTO;
import com.miniurl.dto.GlobalFlagDTO;
import com.miniurl.feature.service.FeatureFlagService;
import com.miniurl.feature.service.GlobalFlagService;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Internal endpoints for inter-service feature flag access.
 * Not exposed through the API Gateway.
 */
@Hidden
@RestController
@RequestMapping("/internal/features")
@RequiredArgsConstructor
public class InternalFeatureController {

    private final FeatureFlagService featureFlagService;
    private final GlobalFlagService globalFlagService;

    /**
     * Get feature flags for a specific role.
     * Used by identity-service for the /api/features endpoint.
     */
    @GetMapping("/by-role/{roleId}")
    public ResponseEntity<List<FeatureFlagDTO>> getFeaturesByRole(@PathVariable Long roleId) {
        List<FeatureFlagDTO> features = featureFlagService.getFeaturesByRole(roleId);
        return ResponseEntity.ok(features);
    }

    /**
     * Get all global flags.
     * Used by identity-service for the /api/features/global endpoint.
     */
    @GetMapping("/global")
    public ResponseEntity<List<GlobalFlagDTO>> getAllGlobalFlags() {
        List<GlobalFlagDTO> flags = globalFlagService.getAllGlobalFlags();
        return ResponseEntity.ok(flags);
    }
}
