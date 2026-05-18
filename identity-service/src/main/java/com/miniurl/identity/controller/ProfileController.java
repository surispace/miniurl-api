package com.miniurl.identity.controller;

import com.miniurl.dto.ApiResponse;
import com.miniurl.dto.ProfileUpdateRequest;
import com.miniurl.enums.Theme;
import com.miniurl.identity.entity.User;
import com.miniurl.identity.exception.ResourceNotFoundException;
import com.miniurl.identity.exception.UnauthorizedException;
import com.miniurl.identity.repository.UserRepository;
import com.miniurl.identity.service.AuthService;
import com.miniurl.identity.service.JwtService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.HashMap;
import java.util.Map;

/**
 * Profile management endpoints.
 * Ported from monolith's ProfileController.
 * Audit logging via SLF4J (no dedicated AuditLogService in microservices).
 * Feature flag check omitted — monolith comments confirm "no feature flag check needed".
 */
@RestController
@RequestMapping("/api/profile")
@Tag(name = "User Profile", description = "Authenticated user profile management")
public class ProfileController {

    private static final Logger log = LoggerFactory.getLogger(ProfileController.class);

    private final UserRepository userRepository;
    private final AuthService authService;
    private final JwtService jwtService;

    public ProfileController(UserRepository userRepository, AuthService authService,
                             JwtService jwtService) {
        this.userRepository = userRepository;
        this.authService = authService;
        this.jwtService = jwtService;
    }

    @Operation(summary = "Get current user profile", description = "Returns the authenticated user's profile information.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Profile retrieved"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Invalid or missing authorization header"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "User not found")
    })
    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> getProfile(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new UnauthorizedException("Invalid authorization header");
        }

        String token = authHeader.substring(7);
        Long userId = jwtService.extractUserId(token);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        Map<String, Object> response = new HashMap<>();
        response.put("id", user.getId());
        response.put("firstName", user.getFirstName());
        response.put("lastName", user.getLastName());
        response.put("email", user.getEmail());
        response.put("username", user.getUsername());
        response.put("role", user.getRole() != null ? user.getRole().getName() : "USER");
        response.put("createdAt", user.getCreatedAt());
        response.put("lastLogin", user.getLastLogin());
        response.put("theme", user.getTheme() != null ? user.getTheme() : Theme.LIGHT);

        return ResponseEntity.ok(ApiResponse.success("Profile retrieved", response));
    }

    @Operation(summary = "Update user profile", description = "Updates the authenticated user's profile fields (firstName, lastName, email, theme).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Profile updated successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid request body"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Invalid or missing authorization header"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "User not found")
    })
    @PutMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateProfile(
            @Valid @RequestBody ProfileUpdateRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new UnauthorizedException("Invalid authorization header");
        }

        String token = authHeader.substring(7);
        Long userId = jwtService.extractUserId(token);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        User updatedUser = authService.updateProfile(
                user.getId(),
                request.getFirstName(),
                request.getLastName(),
                request.getEmail(),
                request.getTheme()
        );

        log.info("PROFILE_UPDATE: User {} (id={}) updated profile", updatedUser.getUsername(), updatedUser.getId());

        Map<String, Object> response = new HashMap<>();
        response.put("firstName", updatedUser.getFirstName());
        response.put("lastName", updatedUser.getLastName());
        response.put("email", updatedUser.getEmail());
        response.put("theme", updatedUser.getTheme());

        return ResponseEntity.ok(ApiResponse.success("Profile updated successfully", response));
    }
}
