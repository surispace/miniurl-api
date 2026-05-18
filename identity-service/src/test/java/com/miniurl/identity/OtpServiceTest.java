package com.miniurl.identity;

import com.miniurl.identity.service.OtpService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OtpService Unit Tests")
class OtpServiceTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private OtpService otpService;

    @BeforeEach
    void setUp() {
        // Inject @Value defaults since we're not in a Spring context
        ReflectionTestUtils.setField(otpService, "otpExpiryMinutes", 5);
        ReflectionTestUtils.setField(otpService, "otpResendCooldownSeconds", 30);
    }

    @Nested
    @DisplayName("Login OTP operations")
    class LoginOtpOperations {

        @Test
        @DisplayName("storeOtp should store OTP with TTL")
        void storeOtpShouldStoreWithTtl() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);

            otpService.storeOtp(1L, "123456");

            verify(valueOperations).set(eq("otp:1"), eq("123456"), eq(5L), eq(TimeUnit.MINUTES));
        }

        @Test
        @DisplayName("getOtp should retrieve stored OTP")
        void getOtpShouldRetrieveStoredOtp() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get("otp:1")).thenReturn("123456");

            String result = otpService.getOtp(1L);

            assertEquals("123456", result);
            verify(valueOperations).get("otp:1");
        }

        @Test
        @DisplayName("getOtp should return null when OTP not found")
        void getOtpShouldReturnNullWhenNotFound() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get("otp:1")).thenReturn(null);

            String result = otpService.getOtp(1L);

            assertNull(result);
        }

        @Test
        @DisplayName("deleteOtp should remove OTP from Redis")
        void deleteOtpShouldRemoveFromRedis() {
            otpService.deleteOtp(1L);

            verify(redisTemplate).delete("otp:1");
        }

        @Test
        @DisplayName("hasValidOtp should return true when key exists")
        void hasValidOtpShouldReturnTrueWhenKeyExists() {
            when(redisTemplate.hasKey("otp:1")).thenReturn(true);

            assertTrue(otpService.hasValidOtp(1L));
        }

        @Test
        @DisplayName("hasValidOtp should return false when key does not exist")
        void hasValidOtpShouldReturnFalseWhenKeyDoesNotExist() {
            when(redisTemplate.hasKey("otp:1")).thenReturn(false);

            assertFalse(otpService.hasValidOtp(1L));
        }
    }

    @Nested
    @DisplayName("OTP cooldown operations")
    class OtpCooldownOperations {

        @Test
        @DisplayName("trySetCooldown should return true when cooldown is set")
        void trySetCooldownShouldReturnTrueWhenSet() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.setIfAbsent(eq("otp:cooldown:1"), eq("1"), eq(30L), eq(TimeUnit.SECONDS)))
                    .thenReturn(true);

            assertTrue(otpService.trySetCooldown(1L));
        }

        @Test
        @DisplayName("trySetCooldown should return false when cooldown already exists")
        void trySetCooldownShouldReturnFalseWhenAlreadyExists() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.setIfAbsent(eq("otp:cooldown:1"), eq("1"), eq(30L), eq(TimeUnit.SECONDS)))
                    .thenReturn(false);

            assertFalse(otpService.trySetCooldown(1L));
        }

        @Test
        @DisplayName("getCooldownRemainingSeconds should return TTL when cooldown is active")
        void getCooldownRemainingSecondsShouldReturnTtlWhenActive() {
            when(redisTemplate.getExpire("otp:cooldown:1", TimeUnit.SECONDS)).thenReturn(15L);

            assertEquals(15L, otpService.getCooldownRemainingSeconds(1L));
        }

        @Test
        @DisplayName("getCooldownRemainingSeconds should return 0 when no cooldown")
        void getCooldownRemainingSecondsShouldReturnZeroWhenNoCooldown() {
            when(redisTemplate.getExpire("otp:cooldown:1", TimeUnit.SECONDS)).thenReturn(null);

            assertEquals(0L, otpService.getCooldownRemainingSeconds(1L));
        }

        @Test
        @DisplayName("getCooldownRemainingSeconds should return 0 when TTL is negative")
        void getCooldownRemainingSecondsShouldReturnZeroWhenTtlNegative() {
            when(redisTemplate.getExpire("otp:cooldown:1", TimeUnit.SECONDS)).thenReturn(-2L);

            assertEquals(0L, otpService.getCooldownRemainingSeconds(1L));
        }
    }

    @Nested
    @DisplayName("Email verification operations")
    class EmailVerificationOperations {

        @Test
        @DisplayName("markEmailVerified should set marker in Redis")
        void markEmailVerifiedShouldSetMarker() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);

            otpService.markEmailVerified(1L);

            verify(valueOperations).set("email_verified:1", "1");
        }

        @Test
        @DisplayName("isEmailVerified should return true when marker exists")
        void isEmailVerifiedShouldReturnTrueWhenMarkerExists() {
            when(redisTemplate.hasKey("email_verified:1")).thenReturn(true);

            assertTrue(otpService.isEmailVerified(1L));
        }

        @Test
        @DisplayName("isEmailVerified should return false when marker does not exist")
        void isEmailVerifiedShouldReturnFalseWhenMarkerDoesNotExist() {
            when(redisTemplate.hasKey("email_verified:1")).thenReturn(false);

            assertFalse(otpService.isEmailVerified(1L));
        }

        @Test
        @DisplayName("deleteEmailVerified should remove marker from Redis")
        void deleteEmailVerifiedShouldRemoveMarker() {
            otpService.deleteEmailVerified(1L);

            verify(redisTemplate).delete("email_verified:1");
        }
    }
}
