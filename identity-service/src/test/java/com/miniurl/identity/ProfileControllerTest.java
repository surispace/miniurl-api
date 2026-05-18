package com.miniurl.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniurl.dto.ProfileUpdateRequest;
import com.miniurl.enums.Theme;
import com.miniurl.identity.config.GlobalExceptionHandler;
import com.miniurl.identity.controller.ProfileController;
import com.miniurl.identity.entity.Role;
import com.miniurl.identity.entity.User;
import com.miniurl.identity.entity.UserStatus;
import com.miniurl.identity.repository.UserRepository;
import com.miniurl.identity.service.AuthService;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ProfileController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
@DisplayName("ProfileController Tests")
class ProfileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private AuthService authService;

    @MockBean
    private JwtService jwtService;

    private User testUser;
    private static final String VALID_JWT = "eyJhbGciOiJSUzI1NiJ9.validJwt";
    private static final String VALID_AUTH_HEADER = "Bearer " + VALID_JWT;

    @BeforeEach
    void setUp() {
        Role userRole = new Role("USER", "Regular user");
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
                .theme(Theme.LIGHT)
                .lastLogin(LocalDateTime.now())
                .build();
    }

    @Nested
    @DisplayName("GET /api/profile")
    class GetProfile {

        @Test
        @DisplayName("Should return profile when valid JWT is provided")
        void shouldReturnProfileWithValidJwt() throws Exception {
            when(jwtService.extractUserId(VALID_JWT)).thenReturn(42L);
            when(userRepository.findById(42L)).thenReturn(Optional.of(testUser));

            mockMvc.perform(get("/api/profile")
                            .header("Authorization", VALID_AUTH_HEADER))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(42))
                    .andExpect(jsonPath("$.data.firstName").value("Test"))
                    .andExpect(jsonPath("$.data.lastName").value("User"))
                    .andExpect(jsonPath("$.data.email").value("test@example.com"))
                    .andExpect(jsonPath("$.data.username").value("testuser"))
                    .andExpect(jsonPath("$.data.role").value("USER"))
                    .andExpect(jsonPath("$.data.theme").value("LIGHT"));
        }

        @Test
        @DisplayName("Should return 401 when no Authorization header")
        void shouldReturn401WhenNoAuthHeader() throws Exception {
            mockMvc.perform(get("/api/profile"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Should return 401 when Authorization header is not Bearer")
        void shouldReturn401WhenNotBearer() throws Exception {
            mockMvc.perform(get("/api/profile")
                            .header("Authorization", "Basic dGVzdDp0ZXN0"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Should return 404 when user not found")
        void shouldReturn404WhenUserNotFound() throws Exception {
            when(jwtService.extractUserId(VALID_JWT)).thenReturn(999L);
            when(userRepository.findById(999L)).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/profile")
                            .header("Authorization", VALID_AUTH_HEADER))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("PUT /api/profile")
    class UpdateProfile {

        @Test
        @DisplayName("Should update profile with valid request")
        void shouldUpdateProfileWithValidRequest() throws Exception {
            ProfileUpdateRequest request = new ProfileUpdateRequest("Updated", "Name", "updated@example.com");
            request.setTheme(Theme.DARK);

            User updatedUser = User.builder()
                    .id(42L)
                    .firstName("Updated")
                    .lastName("Name")
                    .email("updated@example.com")
                    .username("testuser")
                    .theme(Theme.DARK)
                    .build();

            when(jwtService.extractUserId(VALID_JWT)).thenReturn(42L);
            when(userRepository.findById(42L)).thenReturn(Optional.of(testUser));
            when(authService.updateProfile(eq(42L), eq("Updated"), eq("Name"), eq("updated@example.com"), eq(Theme.DARK)))
                    .thenReturn(updatedUser);

            mockMvc.perform(put("/api/profile")
                            .header("Authorization", VALID_AUTH_HEADER)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.firstName").value("Updated"))
                    .andExpect(jsonPath("$.data.lastName").value("Name"))
                    .andExpect(jsonPath("$.data.email").value("updated@example.com"))
                    .andExpect(jsonPath("$.data.theme").value("DARK"));
        }

        @Test
        @DisplayName("Should allow partial update with only firstName")
        void shouldAllowPartialUpdate() throws Exception {
            ProfileUpdateRequest request = new ProfileUpdateRequest("NewFirst", null, null);

            User updatedUser = User.builder()
                    .id(42L)
                    .firstName("NewFirst")
                    .lastName("User")
                    .email("test@example.com")
                    .username("testuser")
                    .theme(Theme.LIGHT)
                    .build();

            when(jwtService.extractUserId(VALID_JWT)).thenReturn(42L);
            when(userRepository.findById(42L)).thenReturn(Optional.of(testUser));
            when(authService.updateProfile(eq(42L), eq("NewFirst"), isNull(), isNull(), isNull()))
                    .thenReturn(updatedUser);

            mockMvc.perform(put("/api/profile")
                            .header("Authorization", VALID_AUTH_HEADER)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.firstName").value("NewFirst"));
        }

        @Test
        @DisplayName("Should return 401 when no Authorization header")
        void shouldReturn401WhenNoAuthHeader() throws Exception {
            ProfileUpdateRequest request = new ProfileUpdateRequest("Test", "User", "test@example.com");

            mockMvc.perform(put("/api/profile")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized());
        }
    }
}
