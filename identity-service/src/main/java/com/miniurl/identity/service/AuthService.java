package com.miniurl.identity.service;

import com.miniurl.dto.NotificationEvent;
import com.miniurl.identity.entity.EmailInvite;
import com.miniurl.identity.entity.Role;
import com.miniurl.enums.Theme;
import com.miniurl.identity.entity.User;
import com.miniurl.identity.entity.UserStatus;
import com.miniurl.identity.entity.VerificationToken;
import com.miniurl.exception.RateLimitCooldownException;
import com.miniurl.exception.ResourceNotFoundException;
import com.miniurl.exception.UnauthorizedException;
import com.miniurl.util.ValidationUtils;
import com.miniurl.identity.repository.RoleRepository;
import com.miniurl.identity.repository.UserRepository;
import com.miniurl.identity.repository.VerificationTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Service
public class AuthService {

    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);
    private static final String ALPHANUMERIC = "0123456789";
    private static final int OTP_LENGTH = 6;

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final VerificationTokenRepository tokenRepository;
    private final OutboxService outboxService;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;
    private final EmailInviteService emailInviteService;
    private final OtpService otpService;
    private final RedisTemplate<String, String> redisTemplate;

    private final SecureRandom secureRandom = new SecureRandom();

    // Redis key prefixes for rate limiting (Issue 7: moved from in-memory to Redis)
    private static final String LOGIN_RATE_LIMIT_PREFIX = "rate:login:";
    private static final String OTP_RATE_LIMIT_PREFIX = "rate:otp:";
    private static final String PASSWORD_RESET_RATE_PREFIX = "rate:pwreset:";
    private static final String SIGNUP_RATE_PREFIX = "rate:signup:";

    // Rate limit configuration
    private static final int LOGIN_MAX_ATTEMPTS = 5;
    private static final int LOGIN_WINDOW_MINUTES = 15;
    private static final int OTP_MAX_ATTEMPTS = 5;
    private static final int OTP_WINDOW_MINUTES = 5;
    private static final int PASSWORD_RESET_COOLDOWN_MINUTES = 20;
    private static final int SIGNUP_COOLDOWN_MINUTES = 12;

    public AuthService(UserRepository userRepository,
                      RoleRepository roleRepository,
                      VerificationTokenRepository tokenRepository,
                      OutboxService outboxService,
                      TokenService tokenService,
                      PasswordEncoder passwordEncoder,
                      EmailInviteService emailInviteService,
                      OtpService otpService,
                      RedisTemplate<String, String> redisTemplate) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.tokenRepository = tokenRepository;
        this.outboxService = outboxService;
        this.tokenService = tokenService;
        this.passwordEncoder = passwordEncoder;
        this.emailInviteService = emailInviteService;
        this.otpService = otpService;
        this.redisTemplate = redisTemplate;
    }

    /**
     * Validate password strength per NIST SP 800-63B guidelines.
     * - Min 8 characters
     * - No complexity requirements (no mandatory special chars, uppercase, etc.)
     * - Must not be a common/breached password
     * - Must not contain the username
     */
    private void validatePasswordStrength(String password, String username) {
        if (password == null || password.isEmpty()) {
            throw new UnauthorizedException("Password cannot be empty");
        }

        if (password.length() < 8) {
            throw new UnauthorizedException("Password must be at least 8 characters long");
        }

        if (ValidationUtils.isCommonPassword(password)) {
            throw new UnauthorizedException("Password is too common. Please choose a stronger password.");
        }

        if (username != null && ValidationUtils.passwordContainsUsername(password, username)) {
            throw new UnauthorizedException("Password must not contain your username");
        }
    }

    // ==================== Redis Rate Limiting Helpers (Issue 7) ====================

    /**
     * Check if a rate-limited action is allowed using a sliding window counter in Redis.
     * @param prefix Redis key prefix
     * @param id User or email identifier
     * @param maxAttempts Maximum allowed attempts in the window
     * @param windowMinutes Window duration in minutes
     * @return true if allowed, false if rate limited
     */
    private boolean checkRateLimit(String prefix, String id, int maxAttempts, int windowMinutes) {
        String key = prefix + id;
        String countStr = redisTemplate.opsForValue().get(key);
        int count = countStr != null ? Integer.parseInt(countStr) : 0;
        return count < maxAttempts;
    }

    /**
     * Record an attempt for a rate-limited action.
     */
    private void recordRateLimitAttempt(String prefix, String id, int windowMinutes) {
        String key = prefix + id;
        redisTemplate.opsForValue().increment(key);
        // Set expiry on first attempt
        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        if (ttl == null || ttl <= 0) {
            redisTemplate.expire(key, windowMinutes, TimeUnit.MINUTES);
        }
    }

    /**
     * Reset rate limit counter for a given key.
     */
    private void resetRateLimit(String prefix, String id) {
        redisTemplate.delete(prefix + id);
    }

    /**
     * Get remaining seconds until rate limit resets.
     */
    private long getRateLimitRetrySeconds(String prefix, String id) {
        Long ttl = redisTemplate.getExpire(prefix + id, TimeUnit.SECONDS);
        return ttl != null && ttl > 0 ? ttl : 0;
    }

    // ==================== Login Rate Limiting (Issue 4: replaces hard lockout) ====================

    public boolean checkLoginRateLimit(Long userId) {
        return checkRateLimit(LOGIN_RATE_LIMIT_PREFIX, String.valueOf(userId),
                LOGIN_MAX_ATTEMPTS, LOGIN_WINDOW_MINUTES);
    }

    public long getLoginRateLimitRetrySeconds(Long userId) {
        return getRateLimitRetrySeconds(LOGIN_RATE_LIMIT_PREFIX, String.valueOf(userId));
    }

    public void recordFailedLoginAttempt(Long userId) {
        recordRateLimitAttempt(LOGIN_RATE_LIMIT_PREFIX, String.valueOf(userId), LOGIN_WINDOW_MINUTES);
    }

    public void resetLoginRateLimit(Long userId) {
        resetRateLimit(LOGIN_RATE_LIMIT_PREFIX, String.valueOf(userId));
    }

    // ==================== OTP Verification Rate Limiting (Issue 2) ====================

    public boolean checkOtpRateLimit(Long userId) {
        return checkRateLimit(OTP_RATE_LIMIT_PREFIX, String.valueOf(userId),
                OTP_MAX_ATTEMPTS, OTP_WINDOW_MINUTES);
    }

    public long getOtpRateLimitRetrySeconds(Long userId) {
        return getRateLimitRetrySeconds(OTP_RATE_LIMIT_PREFIX, String.valueOf(userId));
    }

    public void recordFailedOtpAttempt(Long userId) {
        recordRateLimitAttempt(OTP_RATE_LIMIT_PREFIX, String.valueOf(userId), OTP_WINDOW_MINUTES);
    }

    public void resetOtpRateLimit(Long userId) {
        resetRateLimit(OTP_RATE_LIMIT_PREFIX, String.valueOf(userId));
    }

    // ==================== User Registration ====================

    /**
     * Register a new user with email verification and signup rate limiting
     * @param firstName User's first name
     * @param lastName User's last name
     * @param username Desired username
     * @param password User's password (will be validated for strength)
     * @param invitationToken Invitation token for invited users (required)
     * @return Registered user
     */
    @Transactional
    public User registerUser(String firstName, String lastName, String username, String password, String invitationToken) {
        // Validate invitation token and extract email
        if (invitationToken == null || invitationToken.trim().isEmpty()) {
            throw new UnauthorizedException("Invitation token is required");
        }

        EmailInvite invite = emailInviteService.validateInvite(invitationToken);
        String email = invite.getEmail();
        logger.info("Valid invitation token for email: {}", email);

        // Signup rate limiting via Redis (Issue 7: moved from in-memory ConcurrentHashMap)
        if (!checkRateLimit(SIGNUP_RATE_PREFIX, email.toLowerCase(), 1, SIGNUP_COOLDOWN_MINUTES)) {
            throw new UnauthorizedException("Too many signup attempts. Please try again later.");
        }

        // Validate password strength
        validatePasswordStrength(password, username);

        // Check reserved usernames
        if (ValidationUtils.isReservedUsername(username)) {
            throw new UnauthorizedException("Username '" + username + "' is reserved. Please choose another.");
        }

        // Check if email already exists
        Optional<User> existingUser = userRepository.findByEmail(email);

        // If user exists, check status
        if (existingUser.isPresent()) {
            UserStatus status = existingUser.get().getStatus();

            // Block suspended users from re-registering
            if (status == UserStatus.SUSPENDED) {
                throw new UnauthorizedException("Account suspended!");
            }

            // Block active users (all active users have verified emails via invite)
            if (status == UserStatus.ACTIVE) {
                throw new UnauthorizedException("Email already registered");
            }
        }

        // Check if username already exists
        Optional<User> existingByUsername = userRepository.findByUsername(username);
        if (existingByUsername.isPresent()) {
            UserStatus status = existingByUsername.get().getStatus();

            // Block suspended users
            if (status == UserStatus.SUSPENDED) {
                throw new UnauthorizedException("Account suspended!");
            }

            // Block active users
            if (status == UserStatus.ACTIVE) {
                throw new UnauthorizedException("Username already taken");
            }
        }

        // Get USER role
        Role userRole = roleRepository.findByName("USER")
            .orElseThrow(() -> new RuntimeException("USER role not found"));

        // Determine if this is a returning user (soft-deleted) or new user
        boolean isReturningUser = existingUser.isPresent() && existingUser.get().getStatus() == UserStatus.DELETED;
        boolean isInvitedUser = true;

        // Create or reactivate user
        User user;
        if (existingUser.isPresent()) {
            // Reactivate deleted user
            user = existingUser.get();
            user.setFirstName(firstName);
            user.setLastName(lastName);
            user.setUsername(username);
            user.setPassword(passwordEncoder.encode(password));
            user.setStatus(UserStatus.ACTIVE);
            user.setMustChangePassword(false); // User set their own password

            // Invalidate ALL existing tokens for this user (used and unused)
            tokenService.invalidateAllUserTokens(user.getId(), VerificationToken.TYPE_EMAIL_VERIFICATION);
            tokenService.invalidateAllUserTokens(user.getId(), VerificationToken.TYPE_PASSWORD_RESET);
        } else {
            // Create new user - invited users don't need email verification
            user = User.builder()
                .firstName(firstName)
                .lastName(lastName)
                .email(email)
                .username(username)
                .password(passwordEncoder.encode(password))
                .role(userRole)
                .mustChangePassword(false) // User set their own password
                .status(UserStatus.ACTIVE)
                .build();
        }

        userRepository.save(user);

        // Mark email as verified in Redis (all users come via invite)
        otpService.markEmailVerified(user.getId());

        // Track signup for rate limiting in Redis
        recordRateLimitAttempt(SIGNUP_RATE_PREFIX, email.toLowerCase(), SIGNUP_COOLDOWN_MINUTES);

        logger.info("User registered: {} ({}) - Returning: {}, Invited: {}", username, email, isReturningUser, isInvitedUser);

        // If invited user, accept the invitation after successful registration
        if (isInvitedUser) {
            try {
                emailInviteService.acceptInvite(invitationToken);
                logger.info("Invitation accepted for invited user: {} ({})", username, email);
            } catch (UnauthorizedException e) {
                logger.warn("Failed to accept invitation for user {}: {}", username, e.getMessage());
                // Don't fail the registration, just log the error
            }
        }

        // Send congratulations email (NO verification email needed for invited users)
        try {
            NotificationEvent event = NotificationEvent.builder()
                .eventType("CONGRATULATIONS")
                .toEmail(email)
                .payload(java.util.Map.of("firstName", firstName))
                .build();
            outboxService.saveEvent("USER", String.valueOf(user.getId()), "CONGRATULATIONS", event);
            logger.info("Congratulations email event saved to outbox for: {} ({})", email, username);
        } catch (Exception e) {
            logger.warn("Failed to send congratulations email event to {}: {}", email, e.getMessage());
            // Don't fail the registration, just log the error
        }

        return user;
    }

    /**
     * Verify email invitation token before registration.
     * This endpoint only validates the token - it does NOT create or modify any user.
     * @param token Invitation token from the email invite link
     * @return Email from the invite if token is valid
     * @throws UnauthorizedException if token is invalid, expired, or revoked
     */
    @Transactional(readOnly = true)
    public String verifyEmailInviteToken(String token) {
        try {
            EmailInvite invite = emailInviteService.validateInvite(token);
            logger.info("Invitation token validated for email: {}", invite.getEmail());
            return invite.getEmail();
        } catch (UnauthorizedException e) {
            throw e;
        }
    }

    /**
     * Verify password reset token.
     * Validates token against verification_tokens table (PASSWORD_RESET type).
     * @param token Password reset token
     * @return User email if token is valid
     * @throws UnauthorizedException if token is invalid, expired, or used
     */
    @Transactional(readOnly = true)
    public String verifyResetPasswordToken(String token) {
        Optional<VerificationToken> tokenOpt = tokenService.validateToken(token);

        if (tokenOpt.isEmpty()) {
            throw new UnauthorizedException("Invalid or expired reset token");
        }

        VerificationToken verificationToken = tokenOpt.get();
        User user = verificationToken.getUser();

        logger.info("Reset token validated for user: {}", user.getEmail());
        return user.getEmail();
    }

    /**
     * Set password after email verification (by user ID, not token)
     */
    @Transactional
    public void setPassword(Long userId, String newPassword, boolean isNewUser) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        // Validate new password strength
        validatePasswordStrength(newPassword, user.getUsername());

        user.setPassword(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        user.incrementTokenVersion();  // Invalidate any existing tokens
        userRepository.save(user);

        // Send appropriate welcome email
        try {
            String eventType = isNewUser ? "WELCOME" : "WELCOME_BACK";
            NotificationEvent event = NotificationEvent.builder()
                .eventType(eventType)
                .toEmail(user.getEmail())
                .username(user.getUsername())
                .payload(java.util.Map.of("username", user.getUsername()))
                .build();
            outboxService.saveEvent("USER", String.valueOf(user.getId()), eventType, event);
            logger.info("{} email event saved to outbox for user: {}", eventType, user.getUsername());
        } catch (Exception e) {
            logger.warn("Failed to send welcome email event to {}: {}", user.getUsername(), e.getMessage());
        }
    }

    /**
     * Request password reset with email bombing protection via Redis (Issue 7).
     */
    @Transactional
    public void requestPasswordReset(String email) {
        // Email bombing protection via Redis - max 1 request per 20 minutes
        if (!checkRateLimit(PASSWORD_RESET_RATE_PREFIX, email.toLowerCase(), 1, PASSWORD_RESET_COOLDOWN_MINUTES)) {
            long retrySeconds = getRateLimitRetrySeconds(PASSWORD_RESET_RATE_PREFIX, email.toLowerCase());
            long minutesLeft = (retrySeconds / 60) + 1;
            throw new RateLimitCooldownException(
                String.format("Password reset rate limit exceeded. Please try again in %d %s.",
                    minutesLeft, minutesLeft == 1 ? "minute" : "minutes")
            );
        }

        // Track request for rate limiting BEFORE checking if user exists
        // This prevents timing-based enumeration
        recordRateLimitAttempt(PASSWORD_RESET_RATE_PREFIX, email.toLowerCase(), PASSWORD_RESET_COOLDOWN_MINUTES);

        // Anti-enumeration: don't reveal whether the email exists
        Optional<User> userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            logger.info("Password reset requested for non-existent email: {}", email);
            return;
        }

        User user = userOpt.get();

        if (user.getStatus() != UserStatus.ACTIVE) {
            logger.info("Password reset requested for inactive account: {}", email);
            return;
        }

        // Create password reset token
        VerificationToken resetToken = tokenService.createPasswordResetToken(user);

        // Send reset email
        try {
            NotificationEvent event = NotificationEvent.builder()
                .eventType("PASSWORD_RESET")
                .toEmail(email)
                .username(user.getUsername())
                .payload(java.util.Map.of(
                    "username", user.getUsername(),
                    "token", resetToken.getToken()
                ))
                .build();
            outboxService.saveEvent("USER", String.valueOf(user.getId()), "PASSWORD_RESET", event);
        } catch (Exception e) {
            logger.warn("Failed to send password reset email event to {}: {}", email, e.getMessage());
        }

        logger.info("Password reset requested for: {}", email);
    }

    /**
     * Reset password using token
     */
    @Transactional
    public void resetPassword(String token, String newPassword) {
        Optional<VerificationToken> tokenOpt = tokenService.validateToken(token);

        if (tokenOpt.isEmpty()) {
            throw new UnauthorizedException("Invalid or expired reset token");
        }

        VerificationToken resetToken = tokenOpt.get();
        User user = resetToken.getUser();

        // Validate new password strength
        validatePasswordStrength(newPassword, user.getUsername());

        user.setPassword(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        user.incrementTokenVersion();  // Invalidate all existing tokens
        userRepository.save(user);

        // Mark reset token as used
        resetToken.setUsed(true);
        tokenRepository.save(resetToken);

        // Send password reset confirmation email
        try {
            NotificationEvent event = NotificationEvent.builder()
                .eventType("PASSWORD_RESET_CONFIRM")
                .toEmail(user.getEmail())
                .username(user.getUsername())
                .payload(java.util.Map.of("username", user.getUsername()))
                .build();
            outboxService.saveEvent("USER", String.valueOf(user.getId()), "PASSWORD_RESET_CONFIRM", event);
        } catch (Exception e) {
            logger.warn("Failed to send password reset confirmation email event to {}: {}", user.getEmail(), e.getMessage());
        }

        logger.info("Password reset for user: {}", user.getUsername());
    }

    /**
     * Soft delete user account
     */
    @Transactional
    public void deleteAccount(Long userId, String password) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new UnauthorizedException("Password is incorrect");
        }

        // Send deletion confirmation email
        try {
            NotificationEvent event = NotificationEvent.builder()
                .eventType("ACCOUNT_DELETION")
                .toEmail(user.getEmail())
                .username(user.getUsername())
                .payload(java.util.Map.of("username", user.getUsername()))
                .build();
            outboxService.saveEvent("USER", String.valueOf(user.getId()), "ACCOUNT_DELETION", event);
        } catch (Exception e) {
            logger.warn("Failed to send account deletion email event to {}: {}", user.getEmail(), e.getMessage());
        }

        // Soft delete
        user.setStatus(UserStatus.DELETED);
        user.incrementTokenVersion();  // Invalidate all existing tokens on deletion
        userRepository.save(user);
        otpService.storeTokenVersionByUsername(user.getUsername(), user.getTokenVersion());

        logger.info("Account soft-deleted for user: {}", user.getUsername());
    }

    /**
     * Update user profile
     */
    @Transactional
    public User updateProfile(Long userId, String firstName, String lastName, String email, Theme theme) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        // Only update email if provided and changed
        if (email != null && !user.getEmail().equals(email) && userRepository.existsByEmail(email)) {
            throw new UnauthorizedException("Email already in use");
        }

        // Update only provided fields (partial update)
        if (firstName != null) user.setFirstName(firstName);
        if (lastName != null) user.setLastName(lastName);
        if (email != null) user.setEmail(email);
        if (theme != null) user.setTheme(theme);

        userRepository.save(user);

        logger.info("Profile updated for user: {}", user.getUsername());
        return user;
    }

    /**
     * Update last login time
     */
    @Transactional
    public void updateLastLogin(Long userId) {
        userRepository.findById(userId).ifPresent(user -> {
            user.setLastLogin(LocalDateTime.now());
            userRepository.save(user);
        });
    }

    /**
     * Generate a random password
     */
    private String generateRandomPassword() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%";
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            sb.append(chars.charAt(secureRandom.nextInt(chars.length())));
        }
        return sb.toString();
    }

    /**
     * Generate a random OTP code
     */
    public String generateOtp() {
        StringBuilder sb = new StringBuilder(OTP_LENGTH);
        for (int i = 0; i < OTP_LENGTH; i++) {
            sb.append(ALPHANUMERIC.charAt(secureRandom.nextInt(ALPHANUMERIC.length())));
        }
        return sb.toString();
    }

    /**
     * Send OTP for 2FA login. Reuses existing valid OTP if available, generates new one otherwise.
     * Enforces cooldown between OTP sends via Redis.
     * @param user The authenticated user
     * @throws RateLimitCooldownException if called within cooldown period of last OTP send
     */
    public void sendLoginOtp(User user) {
        // Check cooldown via Redis (Issue 5: cooldown now 60s, applied consistently)
        if (!otpService.trySetCooldown(user.getId())) {
            long remaining = otpService.getCooldownRemainingSeconds(user.getId());
            throw new RateLimitCooldownException(
                String.format("Please wait %d seconds before requesting a new OTP.", remaining));
        }

        if (otpService.hasValidOtp(user.getId())) {
            // Resend same OTP — OTP is still in Redis
            String existingOtp = otpService.getOtp(user.getId());
            try {
                NotificationEvent event = NotificationEvent.builder()
                    .eventType("OTP")
                    .toEmail(user.getEmail())
                    .payload(java.util.Map.of(
                        "otp", existingOtp,
                        "firstName", user.getFirstName()
                    ))
                    .build();
                outboxService.saveEvent("USER", String.valueOf(user.getId()), "OTP", event);
                logger.info("Login OTP resent (same code) event saved to outbox for: {}", user.getEmail());
            } catch (Exception e) {
                logger.warn("Failed to resend login OTP to {}: {}", user.getEmail(), e.getMessage());
            }
        } else {
            // Generate new OTP
            generateAndSendLoginOtp(user);
        }
    }

    /**
     * Generate and send OTP for 2FA login.
     * Generates a 6-digit OTP, stores it in Redis, and sends it via email.
     * @param user The authenticated user
     */
    public void generateAndSendLoginOtp(User user) {
        String otp = generateOtp();
        otpService.storeOtp(user.getId(), otp);

        try {
            NotificationEvent event = NotificationEvent.builder()
                .eventType("OTP")
                .toEmail(user.getEmail())
                .payload(java.util.Map.of(
                    "otp", otp,
                    "firstName", user.getFirstName()
                ))
                .build();
            outboxService.saveEvent("USER", String.valueOf(user.getId()), "OTP", event);
            logger.info("Login OTP sent event saved to outbox for: {}", user.getEmail());
        } catch (Exception e) {
            logger.warn("Failed to send login OTP email event to {}: {}", user.getEmail(), e.getMessage());
            logger.warn("OTP for {}: {}", user.getEmail(), otp);
        }
    }

    /**
     * Resend OTP for 2FA login.
     * Accepts either username or email — whichever was used during login.
     * Reuses existing OTP if still valid, generates new one if expired.
     * Enforces cooldown between consecutive resend requests via Redis.
     */
    public void resendLoginOtp(String usernameOrEmail) {
        User user = userRepository.findByUsername(usernameOrEmail)
            .or(() -> userRepository.findByEmail(usernameOrEmail))
            .orElseThrow(() -> new ResourceNotFoundException("User not found: " + usernameOrEmail));

        if (!otpService.hasValidOtp(user.getId())) {
            throw new UnauthorizedException("No OTP generated. Please login again.");
        }

        // Check resend cooldown via Redis (Issue 5: 60s cooldown)
        if (!otpService.trySetCooldown(user.getId())) {
            long remaining = otpService.getCooldownRemainingSeconds(user.getId());
            throw new RateLimitCooldownException(
                String.format("Please wait %d seconds before requesting a new OTP.", remaining));
        }

        // Reuse existing OTP if still valid, otherwise generate new one
        if (otpService.hasValidOtp(user.getId())) {
            // Resend same OTP
            String existingOtp = otpService.getOtp(user.getId());
            try {
                NotificationEvent event = NotificationEvent.builder()
                    .eventType("OTP")
                    .toEmail(user.getEmail())
                    .payload(java.util.Map.of(
                        "otp", existingOtp,
                        "firstName", user.getFirstName()
                    ))
                    .build();
                outboxService.saveEvent("USER", String.valueOf(user.getId()), "OTP", event);
                logger.info("Login OTP resent (same code) event saved to outbox for: {}", user.getEmail());
            } catch (Exception e) {
                logger.warn("Failed to resend login OTP to {}: {}", user.getEmail(), e.getMessage());
            }
        } else {
            // Generate new OTP
            generateAndSendLoginOtp(user);
        }
    }

    /**
     * Verify OTP for 2FA login.
     * Accepts either username or email — whichever was used during login.
     * Returns the user if OTP is valid, throws exception otherwise.
     */
    public User verifyLoginOtp(String usernameOrEmail, String otp) {
        User user = userRepository.findByUsername(usernameOrEmail)
            .or(() -> userRepository.findByEmail(usernameOrEmail))
            .orElseThrow(() -> new ResourceNotFoundException("User not found: " + usernameOrEmail));

        String storedOtp = otpService.getOtp(user.getId());
        if (storedOtp == null) {
            throw new UnauthorizedException("OTP has expired. Please login again.");
        }

        if (!storedOtp.equals(otp)) {
            throw new UnauthorizedException("Invalid OTP. Please try again.");
        }

        otpService.deleteOtp(user.getId());

        logger.info("Login OTP verified successfully for: {}", user.getUsername());
        return user;
    }
}
