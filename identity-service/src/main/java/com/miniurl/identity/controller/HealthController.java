package com.miniurl.identity.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Health check endpoint matching the monolith's GET /api/health pattern.
 * Returns a simple success response indicating the service is running.
 * This endpoint is public (no authentication required) and is used by
 * load balancers, Kubernetes probes, and monitoring systems.
 */
@RestController
@Tag(name = "Health", description = "Service health check endpoint")
public class HealthController {

    @GetMapping("/api/health")
    @Operation(summary = "Health check", description = "Check if the service is running")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Service is healthy")
    })
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "Service is running"
        ));
    }
}
