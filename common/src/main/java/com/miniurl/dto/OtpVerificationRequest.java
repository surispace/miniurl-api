package com.miniurl.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request to verify OTP for 2FA login.
 * Accepts either username or email — whichever the user logged in with.
 */
@Schema(description = "OTP verification request for 2FA login")
public class OtpVerificationRequest {

    @NotBlank(message = "Username or email is required")
    @Size(max = 255, message = "Username or email must be 255 characters or less")
    @Schema(description = "Username or email — use the same identifier used during login", example = "johndoe")
    private String username;

    @NotBlank(message = "OTP is required")
    @Pattern(regexp = "^[0-9]{6}$", message = "OTP must be a 6-digit number")
    @Schema(description = "6-digit numeric OTP code (e.g., 482156)", example = "482156")
    private String otp;

    public OtpVerificationRequest() {}

    public OtpVerificationRequest(String username, String otp) {
        this.username = username;
        this.otp = otp;
    }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getOtp() { return otp; }
    public void setOtp(String otp) { this.otp = otp; }
}
