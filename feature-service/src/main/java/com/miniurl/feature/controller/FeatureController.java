package com.miniurl.feature.controller;

import com.miniurl.dto.FeatureFlagDTO;
import com.miniurl.dto.GlobalFlagDTO;
import com.miniurl.feature.service.FeatureFlagService;
import com.miniurl.feature.service.GlobalFlagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/features")
@RequiredArgsConstructor
@Tag(name = "Feature Flags", description = "Feature flag management endpoints")
public class FeatureController {

    private final FeatureFlagService featureFlagService;
    private final GlobalFlagService globalFlagService;

    @Operation(summary = "List all feature flags", description = "Returns all feature flags across all roles.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Feature flags retrieved")
    })
    @GetMapping
    public ResponseEntity<List<FeatureFlagDTO>> getAllFeatures() {
        return ResponseEntity.ok(featureFlagService.getAllFeatures());
    }

    @Operation(summary = "Get feature flags by role", description = "Returns feature flags for a specific role ID.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Feature flags retrieved"),
        @ApiResponse(responseCode = "404", description = "Role not found")
    })
    @GetMapping("/role/{roleId}")
    public ResponseEntity<List<FeatureFlagDTO>> getFeaturesByRole(@PathVariable Long roleId) {
        return ResponseEntity.ok(featureFlagService.getFeaturesByRole(roleId));
    }

    @Operation(summary = "Get feature flag by ID", description = "Returns a single feature flag by its ID.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Feature flag retrieved"),
        @ApiResponse(responseCode = "404", description = "Feature flag not found")
    })
    @GetMapping("/{id}")
    public ResponseEntity<FeatureFlagDTO> getFeatureFlag(@PathVariable Long id) {
        return ResponseEntity.ok(featureFlagService.getFeatureFlagById(id));
    }

    @Operation(summary = "Toggle feature flag", description = "Enables or disables a feature flag by ID.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Feature flag toggled"),
        @ApiResponse(responseCode = "404", description = "Feature flag not found")
    })
    @PatchMapping("/{id}/toggle")
    public ResponseEntity<FeatureFlagDTO> toggleFeature(
            @PathVariable Long id, 
            @RequestParam boolean enabled) {
        return ResponseEntity.ok(featureFlagService.toggleFeature(id, enabled));
    }

    @Operation(summary = "Create feature flag", description = "Creates a new feature flag for a specific feature and role.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Feature flag created"),
        @ApiResponse(responseCode = "400", description = "Invalid parameters")
    })
    @PostMapping
    public ResponseEntity<FeatureFlagDTO> createFeatureFlag(
            @RequestParam Long featureId, 
            @RequestParam Long roleId, 
            @RequestParam boolean enabled) {
        return ResponseEntity.ok(featureFlagService.createFeatureFlag(featureId, roleId, enabled));
    }

    @Operation(summary = "Create feature flag in bulk", description = "Creates a new feature and its associated flags for admin and user roles.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Feature flag created"),
        @ApiResponse(responseCode = "400", description = "Invalid parameters")
    })
    @PostMapping("/bulk")
    public ResponseEntity<FeatureFlagDTO> createFeatureBulk(
            @RequestParam String featureKey, 
            @RequestParam String featureName, 
            @RequestParam String description,
            @RequestParam boolean adminEnabled, 
            @RequestParam boolean userEnabled) {
        return ResponseEntity.ok(featureFlagService.createFeatureFlag(featureKey, featureName, description, adminEnabled, userEnabled));
    }

    @Operation(summary = "Delete feature flag", description = "Deletes a feature flag by ID.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Feature flag deleted"),
        @ApiResponse(responseCode = "404", description = "Feature flag not found")
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteFeatureFlag(@PathVariable Long id) {
        featureFlagService.deleteFeatureFlag(id);
        return ResponseEntity.noContent().build();
    }
}
