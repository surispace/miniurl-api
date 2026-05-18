package com.miniurl.identity.controller;

import com.miniurl.dto.ApiResponse;
import com.miniurl.identity.entity.User;
import com.miniurl.identity.exception.ResourceNotFoundException;
import com.miniurl.identity.exception.UnauthorizedException;
import com.miniurl.identity.repository.UserRepository;
import com.miniurl.identity.service.JwtService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Public Feature Flag Controller - accessible by all authenticated users.
 * Returns feature flags for the authenticated user's role.
 * Proxies requests to feature-service via RestTemplate.
 * Ported from monolith's FeatureFlagPublicController.
 */
@RestController
@RequestMapping("/api/features")
@Tag(name = "Feature Flags", description = "Public feature flag access for authenticated users")
public class FeatureFlagPublicController {

    private static final Logger log = LoggerFactory.getLogger(FeatureFlagPublicController.class);

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final RestTemplate restTemplate;

    public FeatureFlagPublicController(UserRepository userRepository,
                                       JwtService jwtService,
                                       RestTemplate restTemplate) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.restTemplate = restTemplate;
    }

    /**
     * Get feature flags for the authenticated user's role.
     */
    @Operation(summary = "Get feature flags for current user", description = "Returns feature flags based on the authenticated user's role.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Features retrieved"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Invalid or missing authorization header"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "User not found")
    })
    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMyRoleFeatures(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new UnauthorizedException("Invalid authorization header");
        }

        String token = authHeader.substring(7);
        Long userId = jwtService.extractUserId(token);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        Long roleId = user.getRole().getId();
        String roleName = user.getRole().getName();

        // Fetch features from feature-service
        List<Map<String, Object>> features = fetchFeaturesByRole(roleId);

        Map<String, Object> data = new HashMap<>();
        data.put("features", features);
        data.put("count", features.size());
        data.put("role", roleName);

        return ResponseEntity.ok(ApiResponse.success(
                "Features for " + roleName + " role retrieved successfully", data));
    }

    /**
     * Get all global flags (no authentication required).
     */
    @Operation(summary = "Get all global flags", description = "Returns all global feature flags. No authentication required.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Global flags retrieved")
    })
    @GetMapping("/global")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getAllGlobalFlags() {
        List<Map<String, Object>> flags = fetchAllGlobalFlags();

        Map<String, Object> data = new HashMap<>();
        data.put("flags", flags);
        data.put("count", flags.size());

        return ResponseEntity.ok(ApiResponse.success("Global flags retrieved successfully", data));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchFeaturesByRole(Long roleId) {
        try {
            String url = "http://feature-service/internal/features/by-role/" + roleId;
            ResponseEntity<ApiResponse> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<ApiResponse>() {}
            );
            if (response.getBody() != null && response.getBody().getData() != null) {
                return (List<Map<String, Object>>) response.getBody().getData();
            }
        } catch (Exception e) {
            log.warn("Failed to fetch features for role {} from feature-service: {}", roleId, e.getMessage());
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchAllGlobalFlags() {
        try {
            String url = "http://feature-service/internal/global-flags";
            ResponseEntity<ApiResponse> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<ApiResponse>() {}
            );
            if (response.getBody() != null && response.getBody().getData() != null) {
                return (List<Map<String, Object>>) response.getBody().getData();
            }
        } catch (Exception e) {
            log.warn("Failed to fetch global flags from feature-service: {}", e.getMessage());
        }
        return List.of();
    }
}
