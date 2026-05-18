package com.miniurl.identity.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class OtpService {

    private static final Logger logger = LoggerFactory.getLogger(OtpService.class);

    private static final String OTP_KEY_PREFIX = "otp:";
    private static final String OTP_COOLDOWN_PREFIX = "otp:cooldown:";
    private static final String EMAIL_VERIFIED_PREFIX = "email_verified:";

    private final RedisTemplate<String, String> redisTemplate;

    @Value("${app.otp.expiry-minutes:5}")
    private int otpExpiryMinutes;

    @Value("${app.otp.resend-cooldown-seconds:30}")
    private int otpResendCooldownSeconds;

    public OtpService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    // ==================== Login OTP ====================

    /**
     * Store OTP for a user with configurable TTL (default 5 minutes).
     * Redis auto-expires the key after TTL.
     */
    public void storeOtp(Long userId, String otp) {
        String key = OTP_KEY_PREFIX + userId;
        redisTemplate.opsForValue().set(key, otp, otpExpiryMinutes, TimeUnit.MINUTES);
        logger.debug("OTP stored for user {} with {} minute TTL", userId, otpExpiryMinutes);
    }

    /**
     * Retrieve stored OTP for a user.
     * @return OTP string or null if expired/not found
     */
    public String getOtp(Long userId) {
        return redisTemplate.opsForValue().get(OTP_KEY_PREFIX + userId);
    }

    /**
     * Delete OTP after successful verification.
     */
    public void deleteOtp(Long userId) {
        redisTemplate.delete(OTP_KEY_PREFIX + userId);
        logger.debug("OTP deleted for user {}", userId);
    }

    /**
     * Check if OTP exists (not expired) for a user.
     */
    public boolean hasValidOtp(Long userId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(OTP_KEY_PREFIX + userId));
    }

    /**
     * Set cooldown marker with configurable TTL (default 30 seconds).
     * Uses SETNX for atomicity — no race conditions.
     * @return true if cooldown was set (no existing cooldown), false if still in cooldown
     */
    public boolean trySetCooldown(Long userId) {
        String key = OTP_COOLDOWN_PREFIX + userId;
        return Boolean.TRUE.equals(
            redisTemplate.opsForValue().setIfAbsent(key, "1", otpResendCooldownSeconds, TimeUnit.SECONDS)
        );
    }

    /**
     * Get remaining cooldown seconds for a user.
     * @return remaining seconds, or 0 if no cooldown
     */
    public long getCooldownRemainingSeconds(Long userId) {
        Long ttl = redisTemplate.getExpire(OTP_COOLDOWN_PREFIX + userId, TimeUnit.SECONDS);
        return ttl != null && ttl > 0 ? ttl : 0;
    }

    // ==================== Email Verification ====================

    /**
     * Mark a user's email as verified in Redis.
     * No TTL — persists until explicitly deleted or Redis is flushed.
     */
    public void markEmailVerified(Long userId) {
        String key = EMAIL_VERIFIED_PREFIX + userId;
        redisTemplate.opsForValue().set(key, "1");
        logger.debug("Email verified marker set for user {}", userId);
    }

    /**
     * Check if a user's email has been verified.
     * Returns true if the Redis key exists.
     */
    public boolean isEmailVerified(Long userId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(EMAIL_VERIFIED_PREFIX + userId));
    }

    /**
     * Remove email verified marker (e.g., on account deletion).
     */
    public void deleteEmailVerified(Long userId) {
        redisTemplate.delete(EMAIL_VERIFIED_PREFIX + userId);
        logger.debug("Email verified marker deleted for user {}", userId);
    }

    // ==================== Token Version (Issue 3: revocation support) ====================

    private static final String TOKEN_VERSION_PREFIX = "user:tokenVersion:";

    /**
     * Store the current token version for a user in Redis.
     * The gateway's TokenVersionValidator checks this against the JWT's tokenVersion claim.
     * Called whenever tokenVersion is incremented (password change, account deactivation).
     */
    public void storeTokenVersion(Long userId, int tokenVersion) {
        String key = TOKEN_VERSION_PREFIX + userId;
        redisTemplate.opsForValue().set(key, String.valueOf(tokenVersion));
        logger.debug("Token version {} stored for user {}", tokenVersion, userId);
    }

    /**
     * Store token version by username (for gateway lookup by JWT subject).
     */
    public void storeTokenVersionByUsername(String username, int tokenVersion) {
        String key = TOKEN_VERSION_PREFIX + username;
        redisTemplate.opsForValue().set(key, String.valueOf(tokenVersion));
        logger.debug("Token version {} stored for username {}", tokenVersion, username);
    }
}
