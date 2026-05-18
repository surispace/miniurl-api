package com.miniurl.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniurl.identity.config.GlobalExceptionHandler;
import com.miniurl.identity.controller.SelfInviteController;
import com.miniurl.identity.entity.EmailInvite;
import com.miniurl.identity.repository.UserRepository;
import com.miniurl.identity.service.EmailInviteService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SelfInviteController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
@DisplayName("SelfInviteController Tests")
class SelfInviteControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private EmailInviteService emailInviteService;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private RestTemplate restTemplate;

    @Nested
    @DisplayName("POST /api/self-invite/send")
    class SendSelfInvite {

        @Test
        @DisplayName("Should send invite when feature enabled and email not registered")
        void shouldSendInviteWhenFeatureEnabled() throws Exception {
            when(restTemplate.getForEntity(
                    eq("http://feature-service/internal/global-flags/GLOBAL_USER_SIGNUP/enabled"),
                    eq(Map.class)))
                    .thenReturn(ResponseEntity.ok(Map.of("enabled", true)));

            when(userRepository.existsByEmail("newuser@example.com")).thenReturn(false);
            when(emailInviteService.createInvite(eq("newuser@example.com"), eq("self-invite")))
                    .thenReturn(new EmailInvite("newuser@example.com", "token123", "self-invite"));

            mockMvc.perform(post("/api/self-invite/send")
                            .param("email", "newuser@example.com"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Invitation sent to: newuser@example.com"));
        }

        @Test
        @DisplayName("Should reject when feature is disabled")
        void shouldRejectWhenFeatureDisabled() throws Exception {
            when(restTemplate.getForEntity(
                    eq("http://feature-service/internal/global-flags/GLOBAL_USER_SIGNUP/enabled"),
                    eq(Map.class)))
                    .thenReturn(ResponseEntity.ok(Map.of("enabled", false)));

            mockMvc.perform(post("/api/self-invite/send")
                            .param("email", "newuser@example.com"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Self-signup is currently disabled"));
        }

        @Test
        @DisplayName("Should reject when feature-service is unreachable (fail-closed)")
        void shouldRejectWhenFeatureServiceUnreachable() throws Exception {
            when(restTemplate.getForEntity(anyString(), eq(Map.class)))
                    .thenThrow(new RuntimeException("Feature service down"));

            mockMvc.perform(post("/api/self-invite/send")
                            .param("email", "newuser@example.com"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Self-signup is currently disabled"));
        }

        @Test
        @DisplayName("Should reject when email already registered")
        void shouldRejectWhenEmailAlreadyRegistered() throws Exception {
            when(restTemplate.getForEntity(
                    eq("http://feature-service/internal/global-flags/GLOBAL_USER_SIGNUP/enabled"),
                    eq(Map.class)))
                    .thenReturn(ResponseEntity.ok(Map.of("enabled", true)));

            when(userRepository.existsByEmail("existing@example.com")).thenReturn(true);

            mockMvc.perform(post("/api/self-invite/send")
                            .param("email", "existing@example.com"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Email already registered: existing@example.com"));
        }
    }
}
