package com.miniurl.dto;

import com.miniurl.enums.Theme;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "Profile update request — all fields optional for partial updates")
public class ProfileUpdateRequest {

    @Size(max = 100, message = "First name must be less than 100 characters")
    @Pattern(regexp = "^[\\p{L}\\s'\\-]*$", message = "First name may only contain letters, spaces, hyphens, and apostrophes")
    @Schema(description = "First name", example = "John")
    private String firstName;

    @Size(max = 100, message = "Last name must be less than 100 characters")
    @Pattern(regexp = "^[\\p{L}\\s'\\-]*$", message = "Last name may only contain letters, spaces, hyphens, and apostrophes")
    @Schema(description = "Last name", example = "Doe")
    private String lastName;

    @Email(message = "Email should be valid")
    @Size(max = 255, message = "Email must be 255 characters or less")
    @Schema(description = "Email address", example = "john@example.com")
    private String email;

    @Schema(description = "UI theme preference", example = "DARK", allowableValues = {"LIGHT", "DARK", "OCEAN", "FOREST"})
    private Theme theme;

    public ProfileUpdateRequest() {}

    public ProfileUpdateRequest(String firstName, String lastName, String email) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
    }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public Theme getTheme() { return theme; }
    public void setTheme(Theme theme) { this.theme = theme; }
}
