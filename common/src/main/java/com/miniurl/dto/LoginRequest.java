package com.miniurl.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "User login request")
public class LoginRequest {

    @NotBlank(message = "Username is required")
    @Size(max = 255, message = "Username must be 255 characters or less")
    @Schema(description = "Username or email (max 255 chars)", example = "johndoe")
    private String username;

    @NotBlank(message = "Password is required")
    @Size(max = 255, message = "Password must be 255 characters or less")
    @Schema(description = "User password (max 255 chars)", example = "MyP@ssw0rd")
    private String password;

    @Size(max = 2000, message = "CAPTCHA token must be 2000 characters or less")
    @Schema(description = "CAPTCHA token from client-side widget (required when CAPTCHA is enabled)",
            example = "03AGdBq24...")
    private String captchaToken;
    
    public LoginRequest() {}
    
    public LoginRequest(String username, String password) {
        this.username = username;
        this.password = password;
    }
    
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getCaptchaToken() { return captchaToken; }
    public void setCaptchaToken(String captchaToken) { this.captchaToken = captchaToken; }
}
