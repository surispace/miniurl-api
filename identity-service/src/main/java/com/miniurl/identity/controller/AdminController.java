package com.miniurl.identity.controller;

import com.miniurl.dto.ApiResponse;
import com.miniurl.dto.PagedResponse;
import com.miniurl.dto.UserResponse;
import com.miniurl.identity.entity.Role;
import com.miniurl.identity.entity.RoleName;
import com.miniurl.identity.entity.User;
import com.miniurl.identity.entity.UserStatus;
import com.miniurl.identity.repository.RoleRepository;
import com.miniurl.identity.repository.UserRepository;
import com.miniurl.identity.service.OtpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Admin-only endpoints for user management and statistics.
 * Ported from monolith's AdminController.
 * Audit logging is done via SLF4J (no dedicated audit service in microservices).
 */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final OtpService otpService;

    public AdminController(UserRepository userRepository, RoleRepository roleRepository, OtpService otpService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.otpService = otpService;
    }

    @GetMapping("/users")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getAllUsers(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size,
            @RequestParam(required = false, defaultValue = "createdAt") String sortBy,
            @RequestParam(required = false, defaultValue = "desc") String sortDirection) {

        String validSortBy = validateUserSortField(sortBy);

        Sort sort = "asc".equalsIgnoreCase(sortDirection)
                ? Sort.by(validSortBy).ascending()
                : Sort.by(validSortBy).descending();

        PageRequest pageRequest = PageRequest.of(page, size, sort);

        Page<User> userPage;

        if (search != null && !search.isEmpty()) {
            List<User> allUsers = userRepository.findByFirstNameContainingOrLastNameContainingOrEmailContaining(
                    search, search, search);
            if (status != null && !status.isEmpty()) {
                try {
                    UserStatus userStatus = UserStatus.valueOf(status.toUpperCase());
                    allUsers = allUsers.stream()
                            .filter(u -> u.getStatus() == userStatus)
                            .collect(Collectors.toList());
                } catch (IllegalArgumentException e) {
                    // Invalid status, ignore filter
                }
            }
            int totalElements = allUsers.size();
            int start = Math.min(page * size, totalElements);
            int end = Math.min(start + size, totalElements);
            List<User> paginatedUsers = start < end ? allUsers.subList(start, end) : List.of();
            userPage = new org.springframework.data.domain.PageImpl<>(paginatedUsers, pageRequest, totalElements);
        } else if (status == null || status.isEmpty()) {
            userPage = userRepository.findAll(pageRequest);
        } else {
            try {
                UserStatus userStatus = UserStatus.valueOf(status.toUpperCase());
                userPage = userRepository.findByStatus(userStatus, pageRequest);
            } catch (IllegalArgumentException e) {
                userPage = userRepository.findAll(pageRequest);
            }
        }

        List<UserResponse> userResponses = userPage.getContent().stream()
                .map(this::toUserResponse)
                .collect(Collectors.toList());

        long activeCount = userRepository.countByStatus(UserStatus.ACTIVE);
        long suspendedCount = userRepository.countByStatus(UserStatus.SUSPENDED);
        long deletedCount = userRepository.countByStatus(UserStatus.DELETED);

        PagedResponse<UserResponse> pagedResponse = PagedResponse.<UserResponse>builder()
                .content(userResponses)
                .page(page)
                .size(size)
                .totalElements(userPage.getTotalElements())
                .sortBy(validSortBy)
                .sortDirection(sortDirection)
                .build();

        Map<String, Object> response = new HashMap<>();
        response.put("pagination", pagedResponse);
        response.put("summary", Map.of(
                "totalUsers", userPage.getTotalElements(),
                "activeUsers", activeCount,
                "suspendedUsers", suspendedCount,
                "deletedUsers", deletedCount
        ));

        return ResponseEntity.ok(ApiResponse.success("Users retrieved", response));
    }

    @GetMapping("/users/{id}")
    public ResponseEntity<ApiResponse<UserResponse>> getUserById(@PathVariable Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new com.miniurl.identity.exception.ResourceNotFoundException("User not found"));
        return ResponseEntity.ok(ApiResponse.success("User retrieved", toUserResponse(user)));
    }

    @GetMapping("/users/search")
    public ResponseEntity<ApiResponse<List<UserResponse>>> searchUsers(@RequestParam String query) {
        List<User> users = userRepository.findByFirstNameContainingOrLastNameContainingOrEmailContaining(
                query, query, query);
        List<UserResponse> userResponses = users.stream()
                .map(this::toUserResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success("Users found", userResponses));
    }

    @PostMapping("/users/{id}/deactivate")
    public ResponseEntity<ApiResponse<Void>> deactivateUser(@PathVariable Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new com.miniurl.identity.exception.ResourceNotFoundException("User not found"));
        user.setStatus(UserStatus.DELETED);
        user.incrementTokenVersion();  // Invalidate all existing tokens (Issue 3)
        userRepository.save(user);
        otpService.storeTokenVersionByUsername(user.getUsername(), user.getTokenVersion());
        log.warn("ADMIN ACTION: User {} (id={}) deactivated", user.getUsername(), id);
        return ResponseEntity.ok(ApiResponse.success("User deactivated successfully"));
    }

    @PostMapping("/users/{id}/activate")
    public ResponseEntity<ApiResponse<Void>> activateUser(@PathVariable Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new com.miniurl.identity.exception.ResourceNotFoundException("User not found"));
        user.setStatus(UserStatus.ACTIVE);
        userRepository.save(user);
        log.warn("ADMIN ACTION: User {} (id={}) activated", user.getUsername(), id);
        return ResponseEntity.ok(ApiResponse.success("User activated successfully"));
    }

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStats() {
        long totalUsers = userRepository.count();
        long activeUsers = userRepository.countByStatus(UserStatus.ACTIVE);
        long suspendedUsers = userRepository.countByStatus(UserStatus.SUSPENDED);
        long deletedUsers = userRepository.countByStatus(UserStatus.DELETED);

        Map<String, Object> response = new HashMap<>();
        response.put("totalUsers", totalUsers);
        response.put("activeUsers", activeUsers);
        response.put("suspendedUsers", suspendedUsers);
        response.put("deletedUsers", deletedUsers);

        return ResponseEntity.ok(ApiResponse.success("Statistics retrieved", response));
    }

    @PostMapping("/users/{id}/suspend")
    public ResponseEntity<ApiResponse<Void>> suspendUser(@PathVariable Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new com.miniurl.identity.exception.ResourceNotFoundException("User not found"));

        if (user.isAdmin()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Cannot suspend admin users"));
        }

        user.setStatus(UserStatus.SUSPENDED);
        user.incrementTokenVersion();  // Invalidate all existing tokens (Issue 3)
        userRepository.save(user);
        otpService.storeTokenVersionByUsername(user.getUsername(), user.getTokenVersion());
        log.warn("ADMIN ACTION: User {} (id={}) suspended", user.getUsername(), id);
        return ResponseEntity.ok(ApiResponse.success("User suspended successfully"));
    }

    @PostMapping("/users/{id}/role")
    public ResponseEntity<ApiResponse<Void>> updateUserRole(
            @PathVariable Long id,
            @RequestParam String roleName) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new com.miniurl.identity.exception.ResourceNotFoundException("User not found"));

        try {
            RoleName newRoleName = RoleName.valueOf(roleName.toUpperCase());
            Role newRole = roleRepository.findByName(newRoleName.name())
                    .orElseThrow(() -> new com.miniurl.identity.exception.ResourceNotFoundException("Role not found"));

            String oldRoleName = user.getRole().getName();
            user.setRole(newRole);
            userRepository.save(user);

            log.warn("ADMIN ACTION: User {} (id={}) role changed from {} to {}",
                    user.getUsername(), id, oldRoleName, newRoleName);

            return ResponseEntity.ok(ApiResponse.success("User role updated successfully"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Invalid role name: " + roleName));
        }
    }

    private UserResponse toUserResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .email(user.getEmail())
                .username(user.getUsername())
                .roleName(user.getRole() != null ? user.getRole().getName() : "USER")
                .createdAt(user.getCreatedAt())
                .lastLogin(user.getLastLogin())
                .status(user.getStatus().name())
                .build();
    }

    private String validateUserSortField(String sortBy) {
        if (sortBy == null || sortBy.trim().isEmpty()) {
            return "createdAt";
        }
        Set<String> allowedFields = Set.of(
                "id", "firstName", "lastName", "email", "username", "createdAt", "lastLogin", "status");
        String field = sortBy.trim();
        if (!allowedFields.contains(field)) {
            return "createdAt";
        }
        return field;
    }
}
