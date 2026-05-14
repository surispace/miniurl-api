package com.miniurl.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniurl.identity.config.GlobalExceptionHandler;
import com.miniurl.identity.controller.AdminController;
import com.miniurl.identity.entity.Role;
import com.miniurl.identity.entity.RoleName;
import com.miniurl.identity.entity.User;
import com.miniurl.identity.entity.UserStatus;
import com.miniurl.identity.repository.RoleRepository;
import com.miniurl.identity.repository.UserRepository;
import com.miniurl.identity.service.OtpService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
@DisplayName("AdminController Tests")
class AdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private RoleRepository roleRepository;

    @MockBean
    private OtpService otpService;

    private User testUser;
    private User adminUser;
    private Role userRole;
    private Role adminRole;

    @BeforeEach
    void setUp() {
        userRole = new Role("USER", "Regular user");
        userRole.setId(1L);

        adminRole = new Role("ADMIN", "Administrator");
        adminRole.setId(2L);

        testUser = User.builder()
                .id(42L)
                .firstName("Test")
                .lastName("User")
                .email("test@example.com")
                .username("testuser")
                .password("encodedPassword")
                .status(UserStatus.ACTIVE)
                .role(userRole)
                .build();

        adminUser = User.builder()
                .id(1L)
                .firstName("Admin")
                .lastName("User")
                .email("admin@example.com")
                .username("admin")
                .password("encodedPassword")
                .status(UserStatus.ACTIVE)
                .role(adminRole)
                .build();
    }

    @Nested
    @DisplayName("GET /api/admin/users")
    class GetAllUsers {

        @Test
        @DisplayName("Should return paginated users")
        void shouldReturnPaginatedUsers() throws Exception {
            Page<User> userPage = new PageImpl<>(List.of(testUser), PageRequest.of(0, 20), 1);
            when(userRepository.findAll(any(PageRequest.class))).thenReturn(userPage);
            when(userRepository.countByStatus(UserStatus.ACTIVE)).thenReturn(1L);
            when(userRepository.countByStatus(UserStatus.SUSPENDED)).thenReturn(0L);
            when(userRepository.countByStatus(UserStatus.DELETED)).thenReturn(0L);

            mockMvc.perform(get("/api/admin/users"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.pagination.content[0].id").value(42))
                    .andExpect(jsonPath("$.data.pagination.content[0].username").value("testuser"))
                    .andExpect(jsonPath("$.data.summary.totalUsers").value(1))
                    .andExpect(jsonPath("$.data.summary.activeUsers").value(1));
        }

        @Test
        @DisplayName("Should filter by status")
        void shouldFilterByStatus() throws Exception {
            Page<User> userPage = new PageImpl<>(List.of(testUser), PageRequest.of(0, 20), 1);
            when(userRepository.findByStatus(eq(UserStatus.ACTIVE), any(PageRequest.class))).thenReturn(userPage);
            when(userRepository.countByStatus(UserStatus.ACTIVE)).thenReturn(1L);
            when(userRepository.countByStatus(UserStatus.SUSPENDED)).thenReturn(0L);
            when(userRepository.countByStatus(UserStatus.DELETED)).thenReturn(0L);

            mockMvc.perform(get("/api/admin/users").param("status", "ACTIVE"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.pagination.content[0].status").value("ACTIVE"));
        }
    }

    @Nested
    @DisplayName("GET /api/admin/users/{id}")
    class GetUserById {

        @Test
        @DisplayName("Should return user by ID")
        void shouldReturnUserById() throws Exception {
            when(userRepository.findById(42L)).thenReturn(Optional.of(testUser));

            mockMvc.perform(get("/api/admin/users/42"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(42))
                    .andExpect(jsonPath("$.data.username").value("testuser"));
        }

        @Test
        @DisplayName("Should return 404 when user not found")
        void shouldReturn404WhenUserNotFound() throws Exception {
            when(userRepository.findById(999L)).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/admin/users/999"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("GET /api/admin/users/search")
    class SearchUsers {

        @Test
        @DisplayName("Should search users by query")
        void shouldSearchUsersByQuery() throws Exception {
            when(userRepository.findByFirstNameContainingOrLastNameContainingOrEmailContaining(
                    eq("test"), eq("test"), eq("test")))
                    .thenReturn(List.of(testUser));

            mockMvc.perform(get("/api/admin/users/search").param("query", "test"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value(42))
                    .andExpect(jsonPath("$.data[0].username").value("testuser"));
        }
    }

    @Nested
    @DisplayName("POST /api/admin/users/{id}/deactivate")
    class DeactivateUser {

        @Test
        @DisplayName("Should deactivate user")
        void shouldDeactivateUser() throws Exception {
            when(userRepository.findById(42L)).thenReturn(Optional.of(testUser));
            when(userRepository.save(any(User.class))).thenReturn(testUser);

            mockMvc.perform(post("/api/admin/users/42/deactivate"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("User deactivated successfully"));
        }
    }

    @Nested
    @DisplayName("POST /api/admin/users/{id}/activate")
    class ActivateUser {

        @Test
        @DisplayName("Should activate user")
        void shouldActivateUser() throws Exception {
            when(userRepository.findById(42L)).thenReturn(Optional.of(testUser));
            when(userRepository.save(any(User.class))).thenReturn(testUser);

            mockMvc.perform(post("/api/admin/users/42/activate"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("User activated successfully"));
        }
    }

    @Nested
    @DisplayName("GET /api/admin/stats")
    class GetStats {

        @Test
        @DisplayName("Should return user statistics")
        void shouldReturnUserStatistics() throws Exception {
            when(userRepository.count()).thenReturn(100L);
            when(userRepository.countByStatus(UserStatus.ACTIVE)).thenReturn(80L);
            when(userRepository.countByStatus(UserStatus.SUSPENDED)).thenReturn(15L);
            when(userRepository.countByStatus(UserStatus.DELETED)).thenReturn(5L);

            mockMvc.perform(get("/api/admin/stats"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalUsers").value(100))
                    .andExpect(jsonPath("$.data.activeUsers").value(80))
                    .andExpect(jsonPath("$.data.suspendedUsers").value(15))
                    .andExpect(jsonPath("$.data.deletedUsers").value(5));
        }
    }

    @Nested
    @DisplayName("POST /api/admin/users/{id}/suspend")
    class SuspendUser {

        @Test
        @DisplayName("Should suspend non-admin user")
        void shouldSuspendNonAdminUser() throws Exception {
            when(userRepository.findById(42L)).thenReturn(Optional.of(testUser));
            when(userRepository.save(any(User.class))).thenReturn(testUser);

            mockMvc.perform(post("/api/admin/users/42/suspend"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("User suspended successfully"));
        }

        @Test
        @DisplayName("Should not suspend admin user")
        void shouldNotSuspendAdminUser() throws Exception {
            when(userRepository.findById(1L)).thenReturn(Optional.of(adminUser));

            mockMvc.perform(post("/api/admin/users/1/suspend"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Cannot suspend admin users"));
        }
    }

    @Nested
    @DisplayName("POST /api/admin/users/{id}/role")
    class UpdateUserRole {

        @Test
        @DisplayName("Should update user role")
        void shouldUpdateUserRole() throws Exception {
            when(userRepository.findById(42L)).thenReturn(Optional.of(testUser));
            when(roleRepository.findByName("ADMIN")).thenReturn(Optional.of(adminRole));
            when(userRepository.save(any(User.class))).thenReturn(testUser);

            mockMvc.perform(post("/api/admin/users/42/role").param("roleName", "ADMIN"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("User role updated successfully"));
        }

        @Test
        @DisplayName("Should return 400 for invalid role name")
        void shouldReturn400ForInvalidRoleName() throws Exception {
            when(userRepository.findById(42L)).thenReturn(Optional.of(testUser));

            mockMvc.perform(post("/api/admin/users/42/role").param("roleName", "INVALID_ROLE"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Invalid role name: INVALID_ROLE"));
        }
    }
}
