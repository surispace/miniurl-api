package com.miniurl.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniurl.identity.config.GlobalExceptionHandler;
import com.miniurl.identity.controller.FeatureFlagPublicController;
import com.miniurl.identity.entity.Role;
import com.miniurl.identity.entity.User;
import com.miniurl.identity.entity.UserStatus;
import com.miniurl.identity.repository.UserRepository;
import com.miniurl.identity.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(FeatureFlagPublicController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
@DisplayName("FeatureFlagPublicController Tests")
class FeatureFlagPublicControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private RestTemplate restTemplate;

    private User testUser;
    private Role userRole;
    private static final String VALID_JWT = "eyJhbGciOiJSUzI1NiJ9.validJwt";
    private static final String VALID_AUTH_HEADER = "Bearer " + VALID_JWT;

    @BeforeEach
    void setUp() {
        userRole = new Role("USER", "Regular user");
        userRole.setId(1L);

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
    }

    @Nested
    @DisplayName("GET /api/features")
    class GetMyRoleFeatures {

        @Test
        @DisplayName("Should return features for authenticated user's role")
        void shouldReturnFeaturesForAuthenticatedUser() throws Exception {
            when(jwtService.extractUserId(VALID_JWT)).thenReturn(42L);
            when(userRepository.findById(42L)).thenReturn(Optional.of(testUser));

            mockMvc.perform(get("/api/features")
                            .header("Authorization", VALID_AUTH_HEADER))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.role").value("USER"))
                    .andExpect(jsonPath("$.data.features").isArray())
                    .andExpect(jsonPath("$.data.count").value(0));
        }

        @Test
        @DisplayName("Should return 401 when no Authorization header")
        void shouldReturn401WhenNoAuthHeader() throws Exception {
            mockMvc.perform(get("/api/features"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Should return 404 when user not found")
        void shouldReturn404WhenUserNotFound() throws Exception {
            when(jwtService.extractUserId(VALID_JWT)).thenReturn(999L);
            when(userRepository.findById(999L)).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/features")
                            .header("Authorization", VALID_AUTH_HEADER))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("GET /api/features/global")
    class GetAllGlobalFlags {

        @Test
        @DisplayName("Should return global flags without authentication")
        void shouldReturnGlobalFlagsWithoutAuth() throws Exception {
            mockMvc.perform(get("/api/features/global"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.flags").isArray())
                    .andExpect(jsonPath("$.data.count").value(0));
        }
    }
}
