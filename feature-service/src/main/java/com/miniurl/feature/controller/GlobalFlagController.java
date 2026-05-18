package com.miniurl.feature.controller;

import com.miniurl.dto.GlobalFlagDTO;
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
@RequestMapping("/api/global-flags")
@RequiredArgsConstructor
@Tag(name = "Global Flags", description = "Global feature flag management endpoints")
public class GlobalFlagController {

    private final GlobalFlagService globalFlagService;

    @Operation(summary = "List all global flags", description = "Returns all global feature flags.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Global flags retrieved")
    })
    @GetMapping
    public ResponseEntity<List<GlobalFlagDTO>> getAllGlobalFlags() {
        return ResponseEntity.ok(globalFlagService.getAllGlobalFlags());
    }

    @Operation(summary = "Get global flag by ID", description = "Returns a single global flag by its ID.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Global flag retrieved"),
        @ApiResponse(responseCode = "404", description = "Global flag not found")
    })
    @GetMapping("/{id}")
    public ResponseEntity<GlobalFlagDTO> getGlobalFlag(@PathVariable Long id) {
        return ResponseEntity.ok(globalFlagService.getGlobalFlagById(id));
    }

    @Operation(summary = "Toggle global flag", description = "Enables or disables a global flag by ID.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Global flag toggled"),
        @ApiResponse(responseCode = "404", description = "Global flag not found")
    })
    @PatchMapping("/{id}/toggle")
    public ResponseEntity<GlobalFlagDTO> toggleGlobalFlag(
            @PathVariable Long id, 
            @RequestParam boolean enabled) {
        return ResponseEntity.ok(globalFlagService.toggleGlobalFlag(id, enabled));
    }

    @Operation(summary = "Create global flag", description = "Creates a new global flag for a specific feature.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Global flag created"),
        @ApiResponse(responseCode = "400", description = "Invalid parameters")
    })
    @PostMapping
    public ResponseEntity<GlobalFlagDTO> createGlobalFlag(
            @RequestParam Long featureId, 
            @RequestParam boolean enabled) {
        return ResponseEntity.ok(globalFlagService.createGlobalFlag(featureId, enabled));
    }

    @Operation(summary = "Create global flag in bulk", description = "Creates a new feature and its global flag.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Global flag created"),
        @ApiResponse(responseCode = "400", description = "Invalid parameters")
    })
    @PostMapping("/bulk")
    public ResponseEntity<GlobalFlagDTO> createGlobalFlagBulk(
            @RequestParam String featureKey, 
            @RequestParam String featureName, 
            @RequestParam String description, 
            @RequestParam boolean enabled) {
        return ResponseEntity.ok(globalFlagService.createGlobalFlag(featureKey, featureName, description, enabled));
    }

    @Operation(summary = "Delete global flag", description = "Deletes a global flag by ID.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Global flag deleted"),
        @ApiResponse(responseCode = "404", description = "Global flag not found")
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteGlobalFlag(@PathVariable Long id) {
        globalFlagService.deleteGlobalFlag(id);
        return ResponseEntity.noContent().build();
    }
}
