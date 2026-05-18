package com.miniurl.dto;

/**
 * Response returned after successful credential validation when 2FA is enabled.
 * Indicates the user must verify their OTP to complete login.
 */
public class LoginOtpResponse {

    private String message;
    private boolean otpRequired;
    private String email;

    public LoginOtpResponse() {}

    public LoginOtpResponse(String message, String email) {
        this.message = message;
        this.otpRequired = true;
        this.email = email;
    }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public boolean isOtpRequired() { return otpRequired; }
    public void setOtpRequired(boolean otpRequired) { this.otpRequired = otpRequired; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
}
