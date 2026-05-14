package com.miniurl.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniurl.dto.LoginRequest;
import com.miniurl.dto.OtpVerificationRequest;
import com.miniurl.dto.ResendOtpRequest;
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

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for Redis-based rate limiting on auth endpoints.
 *
 * After Issue 4 fix: hard lockout (5 failures → 5-min lock, 423 status) was replaced
 * with Redis sliding-window rate limiting (NIST SP 800-63B §5.2.2).
 * All failure responses now return 401 "Invalid credentials" for anti-enumeration.
 */
@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
@DisplayName("AuthController Rate Limiting Tests")
class AuthControllerLockoutTest {

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

    private User activeUser;

    @BeforeEach
    void setUp() {
        activeUser = User.builder()
                .id(1L)
                .firstName("Test")
                .lastName("User")
                .email("test@example.com")
                .username("testuser")
                .password("encodedPassword")
                .status(UserStatus.ACTIVE)
                .build();
    }

    @Nested
    @DisplayName("Login endpoint rate limiting")
    class LoginRateLimiting {

        @Test
        @DisplayName("should return 401 when login is rate-limited")
        void shouldReturnUnauthorizedWhenRateLimited() throws Exception {
            when(userRepository.findByUsername("testuser"))
                    .thenReturn(Optional.of(activeUser));
            when(userRepository.findByEmail("testuser"))
                    .thenReturn(Optional.empty());
            // Rate limiter says too many attempts
            when(authService.checkLoginRateLimit(1L)).thenReturn(false);
            when(authService.getLoginRateLimitRetrySeconds(1L)).thenReturn(300L);

            LoginRequest request = new LoginRequest("testuser", "password");

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("Invalid credentials"));

            // Verify no OTP was sent
            verify(authService, never()).sendLoginOtp(any());
        }

        @Test
        @DisplayName("should record failed attempt on wrong password")
        void shouldRecordFailedAttemptOnWrongPassword() throws Exception {
            when(userRepository.findByUsername("testuser"))
                    .thenReturn(Optional.of(activeUser));
            when(userRepository.findByEmail("testuser"))
                    .thenReturn(Optional.empty());
            when(authService.checkLoginRateLimit(1L)).thenReturn(true);
            when(passwordEncoder.matches("wrongpassword", "encodedPassword"))
                    .thenReturn(false);

            LoginRequest request = new LoginRequest("testuser", "wrongpassword");

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("Invalid credentials"));

            // Verify failed attempt was recorded in Redis rate limiter
            verify(authService).recordFailedLoginAttempt(1L);
        }

        @Test
        @DisplayName("should reset rate limiter on successful password")
        void shouldResetRateLimiterOnSuccess() throws Exception {
            when(userRepository.findByUsername("testuser"))
                    .thenReturn(Optional.of(activeUser));
            when(userRepository.findByEmail("testuser"))
                    .thenReturn(Optional.empty());
            when(authService.checkLoginRateLimit(1L)).thenReturn(true);
            when(passwordEncoder.matches("correctpassword", "encodedPassword"))
                    .thenReturn(true);

            LoginRequest request = new LoginRequest("testuser", "correctpassword");

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());

            // Verify rate limiter was reset on success
            verify(authService).resetLoginRateLimit(1L);
            verify(authService).sendLoginOtp(activeUser);
        }
    }

    @Nested
    @DisplayName("OTP verification rate limiting")
    class OtpVerificationRateLimiting {

        @Test
        @DisplayName("should return 401 when OTP verification is rate-limited")
        void shouldReturnUnauthorizedWhenOtpRateLimited() throws Exception {
            when(userRepository.findByUsername("testuser"))
                    .thenReturn(Optional.of(activeUser));
            when(userRepository.findByEmail("testuser"))
                    .thenReturn(Optional.empty());
            when(authService.checkOtpRateLimit(1L)).thenReturn(false);
            when(authService.getOtpRateLimitRetrySeconds(1L)).thenReturn(300L);

            OtpVerificationRequest request = new OtpVerificationRequest("testuser", "123456");

            mockMvc.perform(post("/api/auth/verify-otp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("Invalid credentials"));

            // Verify OTP was not verified
            verify(authService, never()).verifyLoginOtp(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("Resend OTP anti-enumeration")
    class ResendOtpAntiEnumeration {

        @Test
        @DisplayName("should return 401 for non-existent user")
        void shouldReturnUnauthorizedForNonExistentUser() throws Exception {
            when(userRepository.findByUsername("nonexistent"))
                    .thenReturn(Optional.empty());
            when(userRepository.findByEmail("nonexistent"))
                    .thenReturn(Optional.empty());

            ResendOtpRequest request = new ResendOtpRequest("nonexistent");

            mockMvc.perform(post("/api/auth/resend-otp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("Invalid credentials"));

            // Verify OTP was not resent
            verify(authService, never()).resendLoginOtp(anyString());
        }
    }
}
