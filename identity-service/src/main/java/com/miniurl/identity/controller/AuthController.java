package com.miniurl.identity.controller;

import com.miniurl.dto.ApiResponse;
import com.miniurl.dto.JwtAuthRequest;
import com.miniurl.dto.JwtAuthResponse;
import com.miniurl.dto.LoginRequest;
import com.miniurl.dto.LoginOtpResponse;
import com.miniurl.dto.LoginResponse;
import com.miniurl.dto.OtpVerificationRequest;
import com.miniurl.dto.ResendOtpRequest;
import com.miniurl.dto.SignupRequest;
import com.miniurl.dto.DeleteAccountRequest;
import com.miniurl.dto.ForgotPasswordRequest;
import com.miniurl.dto.ResetPasswordRequest;
import com.miniurl.identity.entity.User;
import com.miniurl.identity.entity.UserPrincipal;
import com.miniurl.identity.exception.AccountLockedException;
import com.miniurl.identity.exception.ResourceNotFoundException;
import com.miniurl.identity.exception.UnauthorizedException;
import com.miniurl.identity.repository.UserRepository;
import com.miniurl.identity.service.AuthService;
import com.miniurl.identity.service.CaptchaService;
import com.miniurl.identity.service.EmailInviteService;
import com.miniurl.identity.service.JwtService;
import com.miniurl.identity.service.TokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "User authentication and registration endpoints")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthService authService;
    private final TokenService tokenService;
    private final EmailInviteService emailInviteService;
    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final CaptchaService captchaService;

    @Operation(summary = "Register a new user", description = "Creates a new user account. Requires CAPTCHA verification.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Account created successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid request or CAPTCHA failure"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Username or email already exists")
    })
    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<JwtAuthResponse>> signup(@Valid @RequestBody SignupRequest request,
                                                                HttpServletRequest httpRequest) {
        // CAPTCHA verification (OWASP ASVS V2.1 — bot detection on auth endpoints)
        captchaService.verifyCaptcha(request.getCaptchaToken(), httpRequest.getRemoteAddr());

        User user = authService.registerUser(
            request.getFirstName(),
            request.getLastName(),
            request.getUsername(),
            request.getPassword(),
            request.getInvitationToken()
        );
        // Include tokenVersion for revocation support (Issue 3)
        String jwt = jwtService.generateToken(new UserPrincipal(user), user.getTokenVersion());
        JwtAuthResponse response = new JwtAuthResponse(
            jwt,
            user.getUsername(),
            user.getId(),
            user.getFirstName(),
            user.getLastName(),
            user.isMustChangePassword()
        );
        return ResponseEntity.ok(ApiResponse.success("Account created successfully", response));
    }

    @Operation(summary = "Verify email invitation token", description = "Validates an email invitation token for signup eligibility.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Email invitation verified successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid or expired invitation token")
    })
    @GetMapping("/verify-email-invite")
    public ResponseEntity<ApiResponse<Void>> verifyEmailInvite(@RequestParam String token) {
        authService.verifyEmailInviteToken(token);
        return ResponseEntity.ok(ApiResponse.success("Email invitation verified successfully"));
    }

    @Operation(summary = "Verify password reset token", description = "Validates a password reset token before allowing password change.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Reset token is valid"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid or expired reset token")
    })
    @GetMapping("/verify-email")
    public ResponseEntity<ApiResponse<Void>> verifyEmail(@RequestParam String token) {
        authService.verifyResetPasswordToken(token);
        return ResponseEntity.ok(ApiResponse.success("Reset token is valid"));
    }

    @Operation(summary = "Request password reset", description = "Sends a password reset link to the registered email. Requires CAPTCHA.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Password reset link sent to email"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid request or CAPTCHA failure")
    })
    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse<Void>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request,
                                                             HttpServletRequest httpRequest) {
        // CAPTCHA verification (OWASP ASVS V2.1 — bot detection on auth endpoints)
        captchaService.verifyCaptcha(request.getCaptchaToken(), httpRequest.getRemoteAddr());

        authService.requestPasswordReset(request.getEmail());
        return ResponseEntity.ok(ApiResponse.success("Password reset link sent to email"));
    }

    @Operation(summary = "Reset password", description = "Resets the user password using a valid reset token.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Password reset successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid or expired reset token")
    })
    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse<Void>> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request.getToken(), request.getNewPassword());
        return ResponseEntity.ok(ApiResponse.success("Password reset successfully"));
    }

    @Operation(summary = "Login with username/password", description = "Authenticates user and sends OTP to email. Requires CAPTCHA. Anti-enumeration: always returns generic response.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OTP sent to registered email"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Invalid credentials (generic response)")
    })
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginOtpResponse>> login(@Valid @RequestBody LoginRequest request,
                                                                HttpServletRequest httpRequest) {
        // CAPTCHA verification (OWASP ASVS V2.1 — bot detection on auth endpoints)
        captchaService.verifyCaptcha(request.getCaptchaToken(), httpRequest.getRemoteAddr());

        // Anti-enumeration: always return the same generic response regardless of
        // whether the user exists, password is wrong, or account is rate-limited.
        // OWASP ASVS V2.1.1 — responses must be identical in timing and content.

        Optional<User> userOpt = userRepository.findByUsername(request.getUsername())
            .or(() -> userRepository.findByEmail(request.getUsername()));

        if (userOpt.isEmpty()) {
            // Simulate password check to prevent timing-based enumeration
            passwordEncoder.matches(request.getPassword(),
                "$2a$10$aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
            log.info("Login attempt for non-existent user: {}", request.getUsername());
            throw new UnauthorizedException("Invalid credentials");
        }

        User user = userOpt.get();

        // Check Redis-based rate limiting (replaces hard lockout — NIST SP 800-63B §5.2.2)
        if (!authService.checkLoginRateLimit(user.getId())) {
            long retryAfter = authService.getLoginRateLimitRetrySeconds(user.getId());
            log.warn("Login rate-limited for user: {}", user.getUsername());
            // Same generic response — don't reveal rate limit status
            throw new UnauthorizedException("Invalid credentials");
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            // Track failed attempt in Redis rate limiter
            authService.recordFailedLoginAttempt(user.getId());
            log.warn("Failed login attempt for user: {}", user.getUsername());
            throw new UnauthorizedException("Invalid credentials");
        }

        // Reset rate limiter on successful password validation
        authService.resetLoginRateLimit(user.getId());

        authService.sendLoginOtp(user);
        LoginOtpResponse response = new LoginOtpResponse("OTP sent to your email", user.getEmail());
        return ResponseEntity.ok(ApiResponse.success("Login successful", response));
    }

    @Operation(summary = "Verify login OTP", description = "Verifies the OTP sent during login and returns a JWT token. Rate-limited per user.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OTP verified, JWT returned"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Invalid OTP or rate-limited")
    })
    @PostMapping("/verify-otp")
    public ResponseEntity<ApiResponse<JwtAuthResponse>> verifyOtp(@Valid @RequestBody OtpVerificationRequest request) {
        // Anti-enumeration: don't reveal whether user exists
        Optional<User> userOpt = userRepository.findByUsername(request.getUsername())
            .or(() -> userRepository.findByEmail(request.getUsername()));

        if (userOpt.isEmpty()) {
            throw new UnauthorizedException("Invalid credentials");
        }

        User user = userOpt.get();

        // Check OTP verification rate limit (OWASP ASVS V2.8)
        if (!authService.checkOtpRateLimit(user.getId())) {
            long retryAfter = authService.getOtpRateLimitRetrySeconds(user.getId());
            log.warn("OTP verification rate-limited for user: {}", user.getUsername());
            throw new UnauthorizedException("Invalid credentials");
        }

        try {
            user = authService.verifyLoginOtp(request.getUsername(), request.getOtp());
        } catch (UnauthorizedException e) {
            // Track failed OTP attempt
            authService.recordFailedOtpAttempt(user.getId());
            throw e;
        }

        // Reset OTP rate limiter on success
        authService.resetOtpRateLimit(user.getId());

        // Include tokenVersion in JWT for revocation support (NIST SP 800-63B §5.2.5)
        String jwt = jwtService.generateToken(new UserPrincipal(user), user.getTokenVersion());
        JwtAuthResponse response = new JwtAuthResponse(
            jwt,
            user.getUsername(),
            user.getId(),
            user.getFirstName(),
            user.getLastName(),
            user.isMustChangePassword()
        );
        return ResponseEntity.ok(ApiResponse.success("OTP verified successfully", response));
    }

    @Operation(summary = "Resend login OTP", description = "Resends the OTP to the user's email. Subject to cooldown period.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OTP resent successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Invalid credentials or cooldown active")
    })
    @PostMapping("/resend-otp")
    public ResponseEntity<ApiResponse<Void>> resendOtp(@Valid @RequestBody ResendOtpRequest request) {
        // Anti-enumeration: don't reveal whether user exists
        Optional<User> userOpt = userRepository.findByUsername(request.getUsername())
            .or(() -> userRepository.findByEmail(request.getUsername()));

        if (userOpt.isEmpty()) {
            throw new UnauthorizedException("Invalid credentials");
        }

        authService.resendLoginOtp(request.getUsername());
        return ResponseEntity.ok(ApiResponse.success("OTP resent successfully"));
    }

    @Operation(summary = "Delete user account", description = "Permanently deletes the authenticated user's account. Requires password confirmation.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Account deleted successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Invalid credentials"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "User not found")
    })
    @PostMapping("/delete-account")
    public ResponseEntity<ApiResponse<Void>> deleteAccount(
            @Valid @RequestBody DeleteAccountRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new UnauthorizedException("Invalid authorization header");
        }

        String token = authHeader.substring(7);
        Long userId = jwtService.extractUserId(token);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        authService.deleteAccount(user.getId(), request.getPassword());
        return ResponseEntity.ok(ApiResponse.success("Account deleted successfully"));
    }
}
