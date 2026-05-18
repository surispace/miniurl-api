package com.miniurl.identity.controller;

import com.miniurl.dto.ApiResponse;
import com.miniurl.identity.repository.UserRepository;
import com.miniurl.identity.service.EmailInviteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * REST Controller for self-invitation.
 * Allows users to invite themselves when USER_SIGNUP feature is enabled.
 * This endpoint is PUBLIC (no authentication required) but checks the USER_SIGNUP global feature
 * via the feature-service.
 * Ported from monolith's SelfInviteController.
 */
@RestController
@RequestMapping("/api/self-invite")
@Tag(name = "Self-Invite", description = "Self-invitation endpoint for public signup")
public class SelfInviteController {

    private static final Logger logger = LoggerFactory.getLogger(SelfInviteController.class);

    private final EmailInviteService emailInviteService;
    private final UserRepository userRepository;
    private final RestTemplate restTemplate;

    public SelfInviteController(EmailInviteService emailInviteService,
                                UserRepository userRepository,
                                RestTemplate restTemplate) {
        this.emailInviteService = emailInviteService;
        this.userRepository = userRepository;
        this.restTemplate = restTemplate;
    }

    @Operation(summary = "Send self-invite", description = "Sends a self-invitation email. Requires GLOBAL_USER_SIGNUP feature to be enabled.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Invitation sent"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Self-signup disabled, email already registered, or invalid email")
    })
    @PostMapping("/send")
    public ResponseEntity<ApiResponse<Void>> sendSelfInvite(@RequestParam("email") String email) {
        try {
            // Check if GLOBAL_USER_SIGNUP feature is enabled via feature-service
            if (!isGlobalFeatureEnabled("GLOBAL_USER_SIGNUP")) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("Self-signup is currently disabled"));
            }

            // Check if email is already registered
            if (userRepository.existsByEmail(email)) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("Email already registered: " + email));
            }

            // Create invitation (no username tracking for self-invite)
            emailInviteService.createInvite(email, "self-invite");

            logger.info("Self-invite sent to {}", email);
            return ResponseEntity.ok(ApiResponse.success("Invitation sent to: " + email));

        } catch (IllegalArgumentException e) {
            logger.error("Invalid email for self-invite: {}", email);
            return ResponseEntity.badRequest().body(ApiResponse.error("Invalid email format"));
        } catch (Exception e) {
            logger.error("Failed to send self-invite to {}: {}", email, e.getMessage());
            return ResponseEntity.badRequest().body(ApiResponse.error("Failed to send invite: " + e.getMessage()));
        }
    }

    /**
     * Checks if a global feature is enabled by calling the feature-service.
     * Uses service discovery (feature-service) with RestTemplate.
     */
    private boolean isGlobalFeatureEnabled(String featureKey) {
        try {
            String url = "http://feature-service/internal/global-flags/" + featureKey + "/enabled";
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
            if (response.getBody() != null) {
                return Boolean.TRUE.equals(response.getBody().get("enabled"));
            }
        } catch (Exception e) {
            logger.warn("Failed to check global feature '{}' from feature-service: {}", featureKey, e.getMessage());
        }
        return false;
    }
}
