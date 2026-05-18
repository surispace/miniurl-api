package com.miniurl.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniurl.dto.DeleteAccountRequest;
import com.miniurl.identity.config.GlobalExceptionHandler;
import com.miniurl.identity.controller.AuthController;
import com.miniurl.identity.entity.User;
import com.miniurl.identity.entity.UserStatus;
import com.miniurl.identity.repository.UserRepository;
import com.miniurl.identity.service.AuthService;
import com.miniurl.identity.service.CaptchaService;
import com.miniurl.identity.service.EmailInviteService;
import com.miniurl.identity.service.JwtService;
import com.miniurl.identity.service.TokenService;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
@DisplayName("AuthController Delete Account Tests")
class AuthControllerDeleteAccountTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuthService authService;

    @MockBean
    private TokenService tokenService;

    @MockBean
    private EmailInviteService emailInviteService;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private PasswordEncoder passwordEncoder;

    @MockBean
    private CaptchaService captchaService;

    private User testUser;
    private static final String VALID_JWT = "eyJhbGciOiJSUzI1NiJ9.validJwt";
    private static final String VALID_AUTH_HEADER = "Bearer " + VALID_JWT;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .id(42L)
                .firstName("Test")
                .lastName("User")
                .email("test@example.com")
                .username("testuser")
                .password("encodedPassword")
                .status(UserStatus.ACTIVE)
                .build();
    }

    @Nested
    @DisplayName("JWT-based identity extraction")
    class JwtBasedIdentity {

        @Test
        @DisplayName("should use JWT userId, not request body userId")
        void shouldUseJwtUsernameNotRequestBodyUserId() throws Exception {
            // Attacker sends userId=999 in body, but JWT says userId=42
            when(jwtService.extractUserId(VALID_JWT)).thenReturn(42L);
            when(userRepository.findById(42L)).thenReturn(Optional.of(testUser));
            doNothing().when(authService).deleteAccount(eq(42L), anyString());

            DeleteAccountRequest request = new DeleteAccountRequest(999L, "correctPassword");

            mockMvc.perform(post("/api/auth/delete-account")
                            .header("Authorization", VALID_AUTH_HEADER)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            // Verify deleteAccount was called with JWT-derived userId (42), NOT body userId (999)
            verify(authService).deleteAccount(eq(42L), eq("correctPassword"));
            verify(authService, never()).deleteAccount(eq(999L), anyString());
        }

        @Test
        @DisplayName("should return 401 when Authorization header is missing")
        void shouldReturnUnauthorizedWhenAuthHeaderMissing() throws Exception {
            DeleteAccountRequest request = new DeleteAccountRequest(42L, "password");

            mockMvc.perform(post("/api/auth/delete-account")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("should return 401 when Authorization header is malformed")
        void shouldReturnUnauthorizedWhenAuthHeaderMalformed() throws Exception {
            DeleteAccountRequest request = new DeleteAccountRequest(42L, "password");

            mockMvc.perform(post("/api/auth/delete-account")
                            .header("Authorization", "NotBearer token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("should return 404 when JWT userId does not match any user")
        void shouldReturnNotFoundWhenJwtUserNotFound() throws Exception {
            when(jwtService.extractUserId(VALID_JWT)).thenReturn(999L);
            when(userRepository.findById(999L)).thenReturn(Optional.empty());

            DeleteAccountRequest request = new DeleteAccountRequest(42L, "password");

            mockMvc.perform(post("/api/auth/delete-account")
                            .header("Authorization", VALID_AUTH_HEADER)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("Password validation")
    class PasswordValidation {

        @Test
        @DisplayName("should successfully delete account with correct password")
        void shouldDeleteAccountWithCorrectPassword() throws Exception {
            when(jwtService.extractUserId(VALID_JWT)).thenReturn(42L);
            when(userRepository.findById(42L)).thenReturn(Optional.of(testUser));
            doNothing().when(authService).deleteAccount(42L, "correctPassword");

            DeleteAccountRequest request = new DeleteAccountRequest(42L, "correctPassword");

            mockMvc.perform(post("/api/auth/delete-account")
                            .header("Authorization", VALID_AUTH_HEADER)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Account deleted successfully"));
        }

        @Test
        @DisplayName("should return 400 when password is wrong")
        void shouldReturnBadRequestWhenPasswordWrong() throws Exception {
            when(jwtService.extractUserId(VALID_JWT)).thenReturn(42L);
            when(userRepository.findById(42L)).thenReturn(Optional.of(testUser));
            doThrow(new com.miniurl.identity.exception.UnauthorizedException("Password is incorrect"))
                    .when(authService).deleteAccount(42L, "wrongPassword");

            DeleteAccountRequest request = new DeleteAccountRequest(42L, "wrongPassword");

            mockMvc.perform(post("/api/auth/delete-account")
                            .header("Authorization", VALID_AUTH_HEADER)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized());
        }
    }
}
