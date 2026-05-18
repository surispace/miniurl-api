package com.miniurl.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "User registration request")
public class SignupRequest {

    @NotBlank(message = "First name is required")
    @Size(min = 1, max = 100, message = "First name must be between 1 and 100 characters")
    @Pattern(regexp = "^[\\p{L}\\s'\\-]+$", message = "First name may only contain letters, spaces, hyphens, and apostrophes")
    @Schema(description = "User's first name (1-100 chars, letters/spaces/hyphens/apostrophes only)", example = "John")
    private String firstName;

    @NotBlank(message = "Last name is required")
    @Size(min = 1, max = 100, message = "Last name must be between 1 and 100 characters")
    @Pattern(regexp = "^[\\p{L}\\s'\\-]+$", message = "Last name may only contain letters, spaces, hyphens, and apostrophes")
    @Schema(description = "User's last name (1-100 chars, letters/spaces/hyphens/apostrophes only)", example = "Doe")
    private String lastName;

    @NotBlank(message = "Username is required")
    @Size(min = 3, max = 50, message = "Username must be between 3 and 50 characters")
    @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_]*$", message = "Username must start with a letter and contain only letters, numbers, and underscores")
    @Schema(description = "Desired username (3-50 chars, starts with letter, alphanumeric + underscore)", example = "johndoe")
    private String username;

    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 255, message = "Password must be between 8 and 255 characters")
    @Schema(description = "User's password (min 8 characters, no complexity requirements)",
            example = "MyP@ssw0rd",
            minLength = 8)
    private String password;

    @NotBlank(message = "Invitation token is required")
    @Size(max = 255, message = "Invitation token must be 255 characters or less")
    @Schema(description = "Invitation token from the email invite link",
            example = "aB3dE5fG7hI9jK1lM3nO5pQ7rS9tU1vW3xY5zA7bC9dE1fG3hI5jK7lM9nO1pQ",
            required = true)
    private String invitationToken;

    @Size(max = 2000, message = "CAPTCHA token must be 2000 characters or less")
    @Schema(description = "CAPTCHA token from client-side widget (required when CAPTCHA is enabled)",
            example = "03AGdBq24...")
    private String captchaToken;

    public SignupRequest() {}

    public SignupRequest(String firstName, String lastName, String username, String password, String invitationToken) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.username = username;
        this.password = password;
        this.invitationToken = invitationToken;
    }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getInvitationToken() { return invitationToken; }
    public void setInvitationToken(String invitationToken) { this.invitationToken = invitationToken; }

    public String getCaptchaToken() { return captchaToken; }
    public void setCaptchaToken(String captchaToken) { this.captchaToken = captchaToken; }
}
