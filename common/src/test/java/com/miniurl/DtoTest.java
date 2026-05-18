package com.miniurl;

import com.miniurl.common.dto.ClickEvent;
import com.miniurl.common.dto.UrlEvent;
import com.miniurl.dto.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DTO Tests")
class DtoTest {

    // ---- ApiResponse ----

    @Test
    @DisplayName("ApiResponse: default constructor and setters")
    void apiResponseDefaultConstructor() {
        ApiResponse<String> response = new ApiResponse<>();
        response.setSuccess(true);
        response.setMessage("ok");
        response.setData("hello");
        assertTrue(response.isSuccess());
        assertEquals("ok", response.getMessage());
        assertEquals("hello", response.getData());
    }

    @Test
    @DisplayName("ApiResponse: parameterized constructor")
    void apiResponseParamConstructor() {
        ApiResponse<Integer> response = new ApiResponse<>(true, "found", 42);
        assertTrue(response.isSuccess());
        assertEquals("found", response.getMessage());
        assertEquals(42, response.getData());
    }

    @Test
    @DisplayName("ApiResponse: static factory methods")
    void apiResponseStaticFactories() {
        ApiResponse<String> success = ApiResponse.success("done");
        assertTrue(success.isSuccess());
        assertEquals("done", success.getMessage());
        assertNull(success.getData());

        ApiResponse<String> successWithData = ApiResponse.success("done", "data");
        assertTrue(successWithData.isSuccess());
        assertEquals("done", successWithData.getMessage());
        assertEquals("data", successWithData.getData());

        ApiResponse<String> error = ApiResponse.error("fail");
        assertFalse(error.isSuccess());
        assertEquals("fail", error.getMessage());
        assertNull(error.getData());
    }

    @Test
    @DisplayName("ApiResponse: builder")
    void apiResponseBuilder() {
        ApiResponse<String> response = ApiResponse.<String>builder()
                .success(true)
                .message("built")
                .data("value")
                .build();
        assertTrue(response.isSuccess());
        assertEquals("built", response.getMessage());
        assertEquals("value", response.getData());
    }

    // ---- UrlResponse ----

    @Test
    @DisplayName("UrlResponse: default constructor and setters")
    void urlResponseDefaultConstructor() {
        UrlResponse urlResponse = new UrlResponse();
        urlResponse.setId(1L);
        urlResponse.setOriginalUrl("https://example.com");
        urlResponse.setShortCode("abc123");
        urlResponse.setShortUrl("https://short.url/abc123");
        urlResponse.setAccessCount(10L);
        LocalDateTime now = LocalDateTime.now();
        urlResponse.setCreatedAt(now);

        assertEquals(1L, urlResponse.getId());
        assertEquals("https://example.com", urlResponse.getOriginalUrl());
        assertEquals("abc123", urlResponse.getShortCode());
        assertEquals("https://short.url/abc123", urlResponse.getShortUrl());
        assertEquals(10L, urlResponse.getAccessCount());
        assertEquals(now, urlResponse.getCreatedAt());
    }

    @Test
    @DisplayName("UrlResponse: parameterized constructor")
    void urlResponseParamConstructor() {
        LocalDateTime now = LocalDateTime.now();
        UrlResponse urlResponse = new UrlResponse(1L, "https://example.com", "abc", "https://short.url/abc", 5L, now);
        assertEquals(1L, urlResponse.getId());
        assertEquals("https://example.com", urlResponse.getOriginalUrl());
        assertEquals("abc", urlResponse.getShortCode());
        assertEquals(5L, urlResponse.getAccessCount());
        assertEquals(now, urlResponse.getCreatedAt());
    }

    @Test
    @DisplayName("UrlResponse: builder")
    void urlResponseBuilder() {
        LocalDateTime now = LocalDateTime.now();
        UrlResponse urlResponse = UrlResponse.builder()
                .id(2L)
                .originalUrl("https://test.com")
                .shortCode("test")
                .shortUrl("https://short.url/test")
                .accessCount(0L)
                .createdAt(now)
                .build();
        assertEquals(2L, urlResponse.getId());
        assertEquals("https://test.com", urlResponse.getOriginalUrl());
        assertEquals("test", urlResponse.getShortCode());
        assertEquals(0L, urlResponse.getAccessCount());
    }

    // ---- CreateUrlRequest ----

    @Test
    @DisplayName("CreateUrlRequest: default constructor and setters")
    void createUrlRequestDefaultConstructor() {
        CreateUrlRequest request = new CreateUrlRequest();
        request.setUrl("https://example.com");
        request.setAlias("myalias");
        assertEquals("https://example.com", request.getUrl());
        assertEquals("myalias", request.getAlias());
    }

    @Test
    @DisplayName("CreateUrlRequest: parameterized constructor")
    void createUrlRequestParamConstructor() {
        CreateUrlRequest request = new CreateUrlRequest("https://example.com", null);
        assertEquals("https://example.com", request.getUrl());
        assertNull(request.getAlias());
    }

    // ---- SignupRequest ----

    @Test
    @DisplayName("SignupRequest: default constructor and setters")
    void signupRequestDefaultConstructor() {
        SignupRequest request = new SignupRequest();
        request.setFirstName("John");
        request.setLastName("Doe");
        request.setUsername("johndoe");
        request.setPassword("SecurePass1");
        request.setInvitationToken("invite-token-123");
        assertEquals("John", request.getFirstName());
        assertEquals("Doe", request.getLastName());
        assertEquals("johndoe", request.getUsername());
        assertEquals("SecurePass1", request.getPassword());
        assertEquals("invite-token-123", request.getInvitationToken());
    }

    @Test
    @DisplayName("SignupRequest: parameterized constructor")
    void signupRequestParamConstructor() {
        SignupRequest request = new SignupRequest("Jane", "Smith", "janesmith", "Pass123!", "token-xyz");
        assertEquals("Jane", request.getFirstName());
        assertEquals("Smith", request.getLastName());
        assertEquals("janesmith", request.getUsername());
        assertEquals("Pass123!", request.getPassword());
        assertEquals("token-xyz", request.getInvitationToken());
    }

    // ---- LoginRequest ----

    @Test
    @DisplayName("LoginRequest: default constructor and setters")
    void loginRequestDefaultConstructor() {
        LoginRequest request = new LoginRequest();
        request.setUsername("johndoe");
        request.setPassword("pass");
        assertEquals("johndoe", request.getUsername());
        assertEquals("pass", request.getPassword());
    }

    @Test
    @DisplayName("LoginRequest: parameterized constructor")
    void loginRequestParamConstructor() {
        LoginRequest request = new LoginRequest("admin", "admin123");
        assertEquals("admin", request.getUsername());
        assertEquals("admin123", request.getPassword());
    }

    // ---- LoginResponse ----

    @Test
    @DisplayName("LoginResponse: default constructor and setters")
    void loginResponseDefaultConstructor() {
        LoginResponse response = new LoginResponse();
        response.setToken("jwt-token");
        response.setUsername("johndoe");
        response.setUserId(1L);
        response.setMustChangePassword(false);
        response.setFirstName("John");
        response.setLastName("Doe");
        assertEquals("jwt-token", response.getToken());
        assertEquals("johndoe", response.getUsername());
        assertEquals(1L, response.getUserId());
        assertFalse(response.isMustChangePassword());
        assertEquals("John", response.getFirstName());
        assertEquals("Doe", response.getLastName());
    }

    @Test
    @DisplayName("LoginResponse: parameterized constructor")
    void loginResponseParamConstructor() {
        LoginResponse response = new LoginResponse("token", "user", 2L, true, "Jane", "Doe");
        assertEquals("token", response.getToken());
        assertEquals("user", response.getUsername());
        assertEquals(2L, response.getUserId());
        assertTrue(response.isMustChangePassword());
        assertEquals("Jane", response.getFirstName());
        assertEquals("Doe", response.getLastName());
    }

    @Test
    @DisplayName("LoginResponse: builder")
    void loginResponseBuilder() {
        LoginResponse response = LoginResponse.builder()
                .token("builder-token")
                .username("builder-user")
                .userId(3L)
                .mustChangePassword(true)
                .firstName("Bob")
                .lastName("Ross")
                .build();
        assertEquals("builder-token", response.getToken());
        assertEquals("builder-user", response.getUsername());
        assertEquals(3L, response.getUserId());
        assertTrue(response.isMustChangePassword());
        assertEquals("Bob", response.getFirstName());
        assertEquals("Ross", response.getLastName());
    }

    // ---- JwtAuthRequest ----

    @Test
    @DisplayName("JwtAuthRequest: default constructor and setters")
    void jwtAuthRequestDefaultConstructor() {
        JwtAuthRequest request = new JwtAuthRequest();
        request.setUsername("admin");
        request.setPassword("admin123");
        assertEquals("admin", request.getUsername());
        assertEquals("admin123", request.getPassword());
    }

    @Test
    @DisplayName("JwtAuthRequest: parameterized constructor")
    void jwtAuthRequestParamConstructor() {
        JwtAuthRequest request = new JwtAuthRequest("user", "pass");
        assertEquals("user", request.getUsername());
        assertEquals("pass", request.getPassword());
    }

    // ---- JwtAuthResponse ----

    @Test
    @DisplayName("JwtAuthResponse: default constructor and setters")
    void jwtAuthResponseDefaultConstructor() {
        JwtAuthResponse response = new JwtAuthResponse();
        response.setToken("token");
        response.setTokenType("Bearer");
        response.setUsername("admin");
        response.setUserId(1L);
        response.setFirstName("Admin");
        response.setLastName("User");
        response.setMustChangePassword(false);
        assertEquals("token", response.getToken());
        assertEquals("Bearer", response.getTokenType());
        assertEquals("admin", response.getUsername());
        assertEquals(1L, response.getUserId());
        assertEquals("Admin", response.getFirstName());
        assertEquals("User", response.getLastName());
        assertFalse(response.isMustChangePassword());
    }

    @Test
    @DisplayName("JwtAuthResponse: parameterized constructor")
    void jwtAuthResponseParamConstructor() {
        JwtAuthResponse response = new JwtAuthResponse("token-val", "admin", 1L, "Admin", "User", false);
        assertEquals("token-val", response.getToken());
        assertEquals("Bearer", response.getTokenType());
        assertEquals("admin", response.getUsername());
        assertEquals(1L, response.getUserId());
        assertEquals("Admin", response.getFirstName());
        assertEquals("User", response.getLastName());
        assertFalse(response.isMustChangePassword());
    }

    @Test
    @DisplayName("JwtAuthResponse: builder")
    void jwtAuthResponseBuilder() {
        JwtAuthResponse response = JwtAuthResponse.builder()
                .token("btoken")
                .username("buser")
                .userId(2L)
                .firstName("First")
                .lastName("Last")
                .mustChangePassword(true)
                .build();
        assertEquals("btoken", response.getToken());
        assertEquals("buser", response.getUsername());
        assertEquals(2L, response.getUserId());
        assertTrue(response.isMustChangePassword());
    }

    // ---- UserResponse ----

    @Test
    @DisplayName("UserResponse: default constructor and setters")
    void userResponseDefaultConstructor() {
        LocalDateTime now = LocalDateTime.now();
        UserResponse response = new UserResponse();
        response.setId(1L);
        response.setFirstName("John");
        response.setLastName("Doe");
        response.setEmail("john@example.com");
        response.setUsername("johndoe");
        response.setRoleName("USER");
        response.setCreatedAt(now);
        response.setLastLogin(now);
        response.setStatus("ACTIVE");
        assertEquals(1L, response.getId());
        assertEquals("John", response.getFirstName());
        assertEquals("john@example.com", response.getEmail());
        assertEquals("USER", response.getRoleName());
        assertEquals("ACTIVE", response.getStatus());
    }

    @Test
    @DisplayName("UserResponse: parameterized constructor")
    void userResponseParamConstructor() {
        LocalDateTime now = LocalDateTime.now();
        UserResponse response = new UserResponse(1L, "Jane", "Doe", "jane@example.com", "janedoe", "ADMIN", now, now, "ACTIVE");
        assertEquals(1L, response.getId());
        assertEquals("Jane", response.getFirstName());
        assertEquals("jane@example.com", response.getEmail());
        assertEquals("ADMIN", response.getRoleName());
    }

    @Test
    @DisplayName("UserResponse: builder")
    void userResponseBuilder() {
        LocalDateTime now = LocalDateTime.now();
        UserResponse response = UserResponse.builder()
                .id(2L)
                .firstName("Bob")
                .lastName("Smith")
                .email("bob@test.com")
                .username("bobsmith")
                .roleName("USER")
                .createdAt(now)
                .lastLogin(now)
                .status("ACTIVE")
                .build();
        assertEquals(2L, response.getId());
        assertEquals("Bob", response.getFirstName());
        assertEquals("bob@test.com", response.getEmail());
        assertEquals("USER", response.getRoleName());
    }

    // ---- PagedResponse ----

    @Test
    @DisplayName("PagedResponse: default constructor and setters")
    void pagedResponseDefaultConstructor() {
        PagedResponse<String> response = new PagedResponse<>();
        response.setContent(List.of("a", "b"));
        response.setPage(0);
        response.setSize(10);
        response.setTotalElements(2L);
        response.setTotalPages(1);
        response.setFirst(true);
        response.setLast(true);
        response.setSortBy("id");
        response.setSortDirection("asc");
        assertEquals(List.of("a", "b"), response.getContent());
        assertEquals(0, response.getPage());
        assertEquals(10, response.getSize());
        assertEquals(2L, response.getTotalElements());
        assertEquals(1, response.getTotalPages());
        assertTrue(response.isFirst());
        assertTrue(response.isLast());
        assertEquals("id", response.getSortBy());
        assertEquals("asc", response.getSortDirection());
    }

    @Test
    @DisplayName("PagedResponse: parameterized constructor computes totalPages and first/last")
    void pagedResponseParamConstructor() {
        List<String> items = List.of("x", "y", "z");
        PagedResponse<String> response = new PagedResponse<>(items, 0, 10, 3L, "createdAt", "desc");
        assertEquals(items, response.getContent());
        assertEquals(0, response.getPage());
        assertEquals(10, response.getSize());
        assertEquals(3L, response.getTotalElements());
        assertEquals(1, response.getTotalPages());
        assertTrue(response.isFirst());
        assertTrue(response.isLast());
        assertEquals("createdAt", response.getSortBy());
        assertEquals("desc", response.getSortDirection());
    }

    @Test
    @DisplayName("PagedResponse: builder")
    void pagedResponseBuilder() {
        PagedResponse<String> response = PagedResponse.<String>builder()
                .content(List.of("a"))
                .page(0)
                .size(20)
                .totalElements(1L)
                .sortBy("id")
                .sortDirection("asc")
                .build();
        assertEquals(List.of("a"), response.getContent());
        assertEquals(0, response.getPage());
        assertEquals(1, response.getTotalPages());
    }

    // ---- PageableRequest ----

    @Test
    @DisplayName("PageableRequest: default constructor uses defaults")
    void pageableRequestDefaults() {
        PageableRequest request = new PageableRequest();
        assertEquals(0, request.getPage());
        assertEquals(10, request.getSize());
        assertEquals("createdAt", request.getSortBy());
        assertEquals("desc", request.getSortDirection());
        assertFalse(request.isAscending());
    }

    @Test
    @DisplayName("PageableRequest: parameterized constructor")
    void pageableRequestParamConstructor() {
        PageableRequest request = new PageableRequest(1, 20, "originalUrl", "asc");
        assertEquals(1, request.getPage());
        assertEquals(20, request.getSize());
        assertEquals("originalUrl", request.getSortBy());
        assertEquals("asc", request.getSortDirection());
        assertTrue(request.isAscending());
    }

    @Test
    @DisplayName("PageableRequest: setters")
    void pageableRequestSetters() {
        PageableRequest request = new PageableRequest();
        request.setPage(2);
        request.setSize(50);
        request.setSortBy("accessCount");
        request.setSortDirection("asc");
        assertEquals(2, request.getPage());
        assertEquals(50, request.getSize());
        assertEquals("accessCount", request.getSortBy());
        assertTrue(request.isAscending());
    }

    // ---- ForgotPasswordRequest ----

    @Test
    @DisplayName("ForgotPasswordRequest")
    void forgotPasswordRequest() {
        ForgotPasswordRequest request = new ForgotPasswordRequest();
        request.setEmail("user@example.com");
        assertEquals("user@example.com", request.getEmail());

        ForgotPasswordRequest request2 = new ForgotPasswordRequest("test@test.com");
        assertEquals("test@test.com", request2.getEmail());
    }

    // ---- ResetPasswordRequest ----

    @Test
    @DisplayName("ResetPasswordRequest")
    void resetPasswordRequest() {
        ResetPasswordRequest request = new ResetPasswordRequest();
        request.setToken("reset-token");
        request.setNewPassword("NewPass123!");
        assertEquals("reset-token", request.getToken());
        assertEquals("NewPass123!", request.getNewPassword());

        ResetPasswordRequest request2 = new ResetPasswordRequest("token-val", "pass1234");
        assertEquals("token-val", request2.getToken());
        assertEquals("pass1234", request2.getNewPassword());
    }

    // ---- DeleteAccountRequest ----

    @Test
    @DisplayName("DeleteAccountRequest")
    void deleteAccountRequest() {
        DeleteAccountRequest request = new DeleteAccountRequest();
        request.setUserId(1L);
        request.setPassword("mypassword");
        assertEquals(1L, request.getUserId());
        assertEquals("mypassword", request.getPassword());

        DeleteAccountRequest request2 = new DeleteAccountRequest(2L, "pass");
        assertEquals(2L, request2.getUserId());
        assertEquals("pass", request2.getPassword());
    }

    // ---- LoginOtpResponse ----

    @Test
    @DisplayName("LoginOtpResponse")
    void loginOtpResponse() {
        LoginOtpResponse response = new LoginOtpResponse();
        response.setMessage("OTP sent");
        response.setOtpRequired(true);
        response.setEmail("user@example.com");
        assertEquals("OTP sent", response.getMessage());
        assertTrue(response.isOtpRequired());
        assertEquals("user@example.com", response.getEmail());

        LoginOtpResponse response2 = new LoginOtpResponse("Please check email", "test@test.com");
        assertEquals("Please check email", response2.getMessage());
        assertTrue(response2.isOtpRequired());
        assertEquals("test@test.com", response2.getEmail());
    }

    // ---- OtpVerificationRequest ----

    @Test
    @DisplayName("OtpVerificationRequest")
    void otpVerificationRequest() {
        OtpVerificationRequest request = new OtpVerificationRequest();
        request.setUsername("johndoe");
        request.setOtp("123456");
        assertEquals("johndoe", request.getUsername());
        assertEquals("123456", request.getOtp());

        OtpVerificationRequest request2 = new OtpVerificationRequest("admin", "482156");
        assertEquals("admin", request2.getUsername());
        assertEquals("482156", request2.getOtp());
    }

    // ---- ResendOtpRequest ----

    @Test
    @DisplayName("ResendOtpRequest")
    void resendOtpRequest() {
        ResendOtpRequest request = new ResendOtpRequest();
        request.setUsername("johndoe");
        assertEquals("johndoe", request.getUsername());

        ResendOtpRequest request2 = new ResendOtpRequest("admin");
        assertEquals("admin", request2.getUsername());
    }

    // ---- ProfileUpdateRequest ----

    @Test
    @DisplayName("ProfileUpdateRequest")
    void profileUpdateRequest() {
        ProfileUpdateRequest request = new ProfileUpdateRequest();
        request.setFirstName("John");
        request.setLastName("Doe");
        request.setEmail("john@example.com");
        assertEquals("John", request.getFirstName());
        assertEquals("Doe", request.getLastName());
        assertEquals("john@example.com", request.getEmail());

        ProfileUpdateRequest request2 = new ProfileUpdateRequest("Jane", "Smith", "jane@test.com");
        assertEquals("Jane", request2.getFirstName());
        assertEquals("Smith", request2.getLastName());
        assertEquals("jane@test.com", request2.getEmail());
    }

    // ---- FeatureFlagDTO ----

    @Test
    @DisplayName("FeatureFlagDTO: default constructor and setters")
    void featureFlagDTODefaultConstructor() {
        LocalDateTime now = LocalDateTime.now();
        FeatureFlagDTO dto = new FeatureFlagDTO();
        dto.setId(1L);
        dto.setFeatureId(10L);
        dto.setFeatureKey("dark-mode");
        dto.setFeatureName("Dark Mode");
        dto.setDescription("Enable dark mode UI");
        dto.setEnabled(true);
        dto.setRoleId(2L);
        dto.setRoleName("ADMIN");
        dto.setCreatedAt(now);
        dto.setUpdatedAt(now);
        assertEquals(1L, dto.getId());
        assertEquals(10L, dto.getFeatureId());
        assertEquals("dark-mode", dto.getFeatureKey());
        assertEquals("Dark Mode", dto.getFeatureName());
        assertTrue(dto.isEnabled());
        assertEquals(2L, dto.getRoleId());
        assertEquals("ADMIN", dto.getRoleName());
        assertEquals(now, dto.getCreatedAt());
        assertEquals(now, dto.getUpdatedAt());
    }

    @Test
    @DisplayName("FeatureFlagDTO: full constructor")
    void featureFlagDTOFullConstructor() {
        LocalDateTime now = LocalDateTime.now();
        FeatureFlagDTO dto = new FeatureFlagDTO(1L, 10L, "feature-key", "Feature", "desc",
                true, 2L, "ADMIN", now, now);
        assertEquals(1L, dto.getId());
        assertEquals(10L, dto.getFeatureId());
        assertEquals("feature-key", dto.getFeatureKey());
        assertTrue(dto.isEnabled());
        assertEquals("ADMIN", dto.getRoleName());
    }

    @Test
    @DisplayName("FeatureFlagDTO: toString")
    void featureFlagDTOToString() {
        FeatureFlagDTO dto = new FeatureFlagDTO();
        dto.setId(1L);
        dto.setFeatureKey("key");
        dto.setFeatureName("Name");
        dto.setEnabled(true);
        dto.setRoleName("USER");
        String str = dto.toString();
        assertTrue(str.contains("id=1"));
        assertTrue(str.contains("featureKey='key'"));
        assertTrue(str.contains("featureName='Name'"));
        assertTrue(str.contains("enabled=true"));
        assertTrue(str.contains("roleName=USER"));
    }

    // ---- GlobalFlagDTO ----

    @Test
    @DisplayName("GlobalFlagDTO: default constructor and setters")
    void globalFlagDTODefaultConstructor() {
        LocalDateTime now = LocalDateTime.now();
        GlobalFlagDTO dto = new GlobalFlagDTO();
        dto.setId(1L);
        dto.setFeatureId(10L);
        dto.setFeatureKey("maintenance");
        dto.setFeatureName("Maintenance Mode");
        dto.setDescription("Global maintenance toggle");
        dto.setEnabled(false);
        dto.setCreatedAt(now);
        dto.setUpdatedAt(now);
        assertEquals(1L, dto.getId());
        assertEquals(10L, dto.getFeatureId());
        assertEquals("maintenance", dto.getFeatureKey());
        assertEquals("Maintenance Mode", dto.getFeatureName());
        assertFalse(dto.isEnabled());
        assertEquals(now, dto.getCreatedAt());
        assertEquals(now, dto.getUpdatedAt());
    }

    @Test
    @DisplayName("GlobalFlagDTO: full constructor")
    void globalFlagDTOFullConstructor() {
        LocalDateTime now = LocalDateTime.now();
        GlobalFlagDTO dto = new GlobalFlagDTO(1L, 10L, "gkey", "Global", "desc",
                true, now, now);
        assertEquals(1L, dto.getId());
        assertEquals(10L, dto.getFeatureId());
        assertEquals("gkey", dto.getFeatureKey());
        assertTrue(dto.isEnabled());
    }

    @Test
    @DisplayName("GlobalFlagDTO: toString")
    void globalFlagDTOToString() {
        GlobalFlagDTO dto = new GlobalFlagDTO();
        dto.setId(1L);
        dto.setFeatureKey("gkey");
        dto.setFeatureName("GName");
        dto.setEnabled(false);
        String str = dto.toString();
        assertTrue(str.contains("id=1"));
        assertTrue(str.contains("featureKey='gkey'"));
        assertTrue(str.contains("featureName='GName'"));
        assertTrue(str.contains("enabled=false"));
    }

    // ---- ClickEvent (Lombok) ----

    @Test
    @DisplayName("ClickEvent: builder, getters, equals")
    void clickEvent() {
        LocalDateTime now = LocalDateTime.now();
        ClickEvent event = ClickEvent.builder()
                .shortCode("abc")
                .originalUrl("https://example.com")
                .ipAddress("192.168.1.1")
                .userAgent("Mozilla/5.0")
                .referer("https://google.com")
                .timestamp(now)
                .userId(1L)
                .build();
        assertEquals("abc", event.getShortCode());
        assertEquals("https://example.com", event.getOriginalUrl());
        assertEquals("192.168.1.1", event.getIpAddress());
        assertEquals("Mozilla/5.0", event.getUserAgent());
        assertEquals("https://google.com", event.getReferer());
        assertEquals(now, event.getTimestamp());
        assertEquals(1L, event.getUserId());

        // Verify no-args constructor and setters
        ClickEvent event2 = new ClickEvent();
        event2.setShortCode("xyz");
        assertEquals("xyz", event2.getShortCode());

        // Verify all-args constructor
        ClickEvent event3 = new ClickEvent("xyz", "url", "ip", "ua", "ref", now, 2L);
        assertEquals("xyz", event3.getShortCode());
        assertEquals(2L, event3.getUserId());
    }

    // ---- UrlEvent (Lombok) ----

    @Test
    @DisplayName("UrlEvent: builder, getters, equals")
    void urlEvent() {
        UrlEvent event = UrlEvent.builder()
                .urlId(1L)
                .shortCode("abc")
                .originalUrl("https://example.com")
                .userId(10L)
                .eventType("CREATED")
                .build();
        assertEquals(1L, event.getUrlId());
        assertEquals("abc", event.getShortCode());
        assertEquals("https://example.com", event.getOriginalUrl());
        assertEquals(10L, event.getUserId());
        assertEquals("CREATED", event.getEventType());

        // Verify no-args constructor and setters
        UrlEvent event2 = new UrlEvent();
        event2.setEventType("DELETED");
        assertEquals("DELETED", event2.getEventType());

        // Verify all-args constructor
        UrlEvent event3 = new UrlEvent(2L, "xyz", "url", 20L, "CREATED");
        assertEquals(2L, event3.getUrlId());
        assertEquals("xyz", event3.getShortCode());
    }

    // ---- NotificationEvent (Lombok) ----

    @Test
    @DisplayName("NotificationEvent: builder, getters")
    void notificationEvent() {
        NotificationEvent event = NotificationEvent.builder()
                .eventType("OTP")
                .toEmail("user@example.com")
                .username("johndoe")
                .payload(Map.of("code", "123456"))
                .build();
        assertEquals("OTP", event.getEventType());
        assertEquals("user@example.com", event.getToEmail());
        assertEquals("johndoe", event.getUsername());
        assertEquals(Map.of("code", "123456"), event.getPayload());

        // Verify no-args constructor
        NotificationEvent event2 = new NotificationEvent();
        event2.setEventType("WELCOME");
        assertEquals("WELCOME", event2.getEventType());

        // Verify all-args constructor
        NotificationEvent event3 = new NotificationEvent("RESET", "a@b.com", "user", null);
        assertEquals("RESET", event3.getEventType());
        assertEquals("a@b.com", event3.getToEmail());
    }
}
