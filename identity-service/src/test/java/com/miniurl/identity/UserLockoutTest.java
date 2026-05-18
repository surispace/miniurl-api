package com.miniurl.identity;

import com.miniurl.identity.entity.User;
import com.miniurl.identity.entity.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("User Account Lockout Tests")
class UserLockoutTest {

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .firstName("Test")
                .lastName("User")
                .email("test@example.com")
                .username("testuser")
                .password("encodedPassword")
                .status(UserStatus.ACTIVE)
                .build();
    }

    @Nested
    @DisplayName("Failed login attempt tracking")
    class FailedAttemptTracking {

        @Test
        @DisplayName("should start with zero failed attempts")
        void shouldStartWithZeroFailedAttempts() {
            assertEquals(0, user.getFailedLoginAttempts());
        }

        @Test
        @DisplayName("should increment failed attempts")
        void shouldIncrementFailedAttempts() {
            user.incrementFailedLoginAttempts();
            assertEquals(1, user.getFailedLoginAttempts());
        }

        @Test
        @DisplayName("should accumulate multiple failed attempts")
        void shouldAccumulateMultipleFailedAttempts() {
            user.incrementFailedLoginAttempts();
            user.incrementFailedLoginAttempts();
            user.incrementFailedLoginAttempts();
            assertEquals(3, user.getFailedLoginAttempts());
        }

        @Test
        @DisplayName("should reset failed attempts to zero")
        void shouldResetFailedAttempts() {
            user.incrementFailedLoginAttempts();
            user.incrementFailedLoginAttempts();
            user.incrementFailedLoginAttempts();
            assertEquals(3, user.getFailedLoginAttempts());

            user.resetFailedLoginAttempts();
            assertEquals(0, user.getFailedLoginAttempts());
        }
    }

    @Nested
    @DisplayName("Account lockout at threshold")
    class LockoutAtThreshold {

        @Test
        @DisplayName("should not lock before 5 failed attempts")
        void shouldNotLockBeforeThreshold() {
            for (int i = 0; i < 4; i++) {
                user.incrementFailedLoginAttempts();
            }
            assertFalse(user.isAccountLocked());
            assertTrue(user.isLoginAttemptAllowed());
        }

        @Test
        @DisplayName("should lock at exactly 5 failed attempts")
        void shouldLockAtThreshold() {
            for (int i = 0; i < 5; i++) {
                user.incrementFailedLoginAttempts();
            }
            assertTrue(user.isAccountLocked());
            assertFalse(user.isLoginAttemptAllowed());
        }

        @Test
        @DisplayName("should remain locked after 6+ failed attempts")
        void shouldRemainLockedBeyondThreshold() {
            for (int i = 0; i < 7; i++) {
                user.incrementFailedLoginAttempts();
            }
            assertTrue(user.isAccountLocked());
            assertFalse(user.isLoginAttemptAllowed());
        }

        @Test
        @DisplayName("should set lockout time 5 minutes in the future")
        void shouldSetLockoutTimeFiveMinutes() {
            LocalDateTime before = LocalDateTime.now();
            for (int i = 0; i < 5; i++) {
                user.incrementFailedLoginAttempts();
            }
            assertNotNull(user.getLockoutTime());
            assertTrue(user.getLockoutTime().isAfter(before));
            assertTrue(user.getLockoutTime().isBefore(before.plusMinutes(6)));
        }
    }

    @Nested
    @DisplayName("Lockout expiry")
    class LockoutExpiry {

        @Test
        @DisplayName("should detect expired lockout")
        void shouldDetectExpiredLockout() {
            // Simulate a lockout that happened 6 minutes ago
            user.incrementFailedLoginAttempts();
            user.incrementFailedLoginAttempts();
            user.incrementFailedLoginAttempts();
            user.incrementFailedLoginAttempts();
            user.incrementFailedLoginAttempts();
            // Manually set lockoutTime to the past
            user.setLockoutTime(LocalDateTime.now().minusMinutes(6));

            assertTrue(user.isLockoutExpired());
        }

        @Test
        @DisplayName("should allow login after lockout expires")
        void shouldAllowLoginAfterLockoutExpires() {
            for (int i = 0; i < 5; i++) {
                user.incrementFailedLoginAttempts();
            }
            // Simulate lockout expiry
            user.setLockoutTime(LocalDateTime.now().minusMinutes(6));

            assertFalse(user.isAccountLocked());
            assertTrue(user.isLoginAttemptAllowed());
        }

        @Test
        @DisplayName("should not detect expired when still locked")
        void shouldNotDetectExpiredWhenStillLocked() {
            for (int i = 0; i < 5; i++) {
                user.incrementFailedLoginAttempts();
            }
            assertFalse(user.isLockoutExpired());
        }
    }

    @Nested
    @DisplayName("Reset behavior")
    class ResetBehavior {

        @Test
        @DisplayName("should clear lockout time on reset")
        void shouldClearLockoutTimeOnReset() {
            for (int i = 0; i < 5; i++) {
                user.incrementFailedLoginAttempts();
            }
            assertNotNull(user.getLockoutTime());

            user.resetFailedLoginAttempts();
            assertNull(user.getLockoutTime());
        }

        @Test
        @DisplayName("should allow login immediately after reset")
        void shouldAllowLoginAfterReset() {
            for (int i = 0; i < 5; i++) {
                user.incrementFailedLoginAttempts();
            }
            assertFalse(user.isLoginAttemptAllowed());

            user.resetFailedLoginAttempts();
            assertTrue(user.isLoginAttemptAllowed());
        }
    }
}
