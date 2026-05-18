package com.miniurl.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniurl.dto.DeleteAccountRequest;
import com.miniurl.identity.config.GlobalExceptionHandler;
import com.miniurl.identity.controller.SettingsController;
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
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SettingsController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
@DisplayName("SettingsController Tests")
class SettingsControllerTest {

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

    @MockBean
    private RestTemplate restTemplate;

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
                .build();
    }

    @Nested
    @DisplayName("GET /api/settings/export")
    class ExportData {

        @Test
        @DisplayName("Should return export data with valid JWT")
        void shouldReturnExportDataWithValidJwt() throws Exception {
            when(jwtService.extractUserId(VALID_JWT)).thenReturn(42L);
            when(userRepository.findById(42L)).thenReturn(Optional.of(testUser));

            mockMvc.perform(get("/api/settings/export")
                            .header("Authorization", VALID_AUTH_HEADER))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.user.id").value(42))
                    .andExpect(jsonPath("$.user.firstName").value("Test"))
                    .andExpect(jsonPath("$.user.lastName").value("User"))
                    .andExpect(jsonPath("$.user.email").value("test@example.com"))
                    .andExpect(jsonPath("$.user.username").value("testuser"))
                    .andExpect(jsonPath("$.user.role").value("USER"))
                    .andExpect(jsonPath("$.urls").isArray())
                    .andExpect(header().string("Content-Disposition",
                            "form-data; name=\"attachment\"; filename=\"miniurl-export-testuser.json\""));
        }

        @Test
        @DisplayName("Should return 401 when no Authorization header")
        void shouldReturn401WhenNoAuthHeader() throws Exception {
            mockMvc.perform(get("/api/settings/export"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Should return 404 when user not found")
        void shouldReturn404WhenUserNotFound() throws Exception {
            when(jwtService.extractUserId(VALID_JWT)).thenReturn(999L);
            when(userRepository.findById(999L)).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/settings/export")
                            .header("Authorization", VALID_AUTH_HEADER))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("POST /api/settings/delete-account")
    class DeleteAccount {

        @Test
        @DisplayName("Should delete account with valid JWT and password")
        void shouldDeleteAccountWithValidJwtAndPassword() throws Exception {
            DeleteAccountRequest request = new DeleteAccountRequest(42L, "TestPass123!@#");

            when(jwtService.extractUserId(VALID_JWT)).thenReturn(42L);
            when(userRepository.findById(42L)).thenReturn(Optional.of(testUser));
            doNothing().when(authService).deleteAccount(eq(42L), eq("TestPass123!@#"));

            mockMvc.perform(post("/api/settings/delete-account")
                            .header("Authorization", VALID_AUTH_HEADER)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Account deleted successfully"));
        }

        @Test
        @DisplayName("Should return 401 when no Authorization header")
        void shouldReturn401WhenNoAuthHeader() throws Exception {
            DeleteAccountRequest request = new DeleteAccountRequest(42L, "TestPass123!@#");

            mockMvc.perform(post("/api/settings/delete-account")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Should return 400 when password is blank")
        void shouldReturn400WhenPasswordBlank() throws Exception {
            DeleteAccountRequest request = new DeleteAccountRequest(42L, "");

            when(jwtService.extractUserId(VALID_JWT)).thenReturn(42L);
            when(userRepository.findById(42L)).thenReturn(Optional.of(testUser));

            mockMvc.perform(post("/api/settings/delete-account")
                            .header("Authorization", VALID_AUTH_HEADER)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }
    }
}
