package com.miniurl.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * JWT Authentication Response DTO.
 * Contains the JWT token and user information.
 */
@Schema(description = "JWT Authentication Response")
public class JwtAuthResponse {

    @Schema(description = "JWT access token", example = "eyJhbGciOiJIUzUxMiJ9.eyJzdWIiOiJhZG1pbiIsImlhdCI6MTY5ODc2NTQzMiwiZXhwIjoxNjk4NzY5MDMyfQ...")
    private String token;

    @Schema(description = "Token type", example = "Bearer")
    private String tokenType;

    @Schema(description = "Username", example = "admin")
    private String username;

    @Schema(description = "User ID", example = "1")
    private Long userId;

    @Schema(description = "User's first name", example = "Admin")
    private String firstName;

    @Schema(description = "User's last name", example = "User")
    private String lastName;

    @Schema(description = "Whether password must be changed on next login", example = "false")
    private boolean mustChangePassword;

    public JwtAuthResponse() {}

    public JwtAuthResponse(String token, String username, Long userId, String firstName, String lastName, boolean mustChangePassword) {
        this.token = token;
        this.tokenType = "Bearer";
        this.username = username;
        this.userId = userId;
        this.firstName = firstName;
        this.lastName = lastName;
        this.mustChangePassword = mustChangePassword;
    }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public String getTokenType() { return tokenType; }
    public void setTokenType(String tokenType) { this.tokenType = tokenType; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public boolean isMustChangePassword() { return mustChangePassword; }
    public void setMustChangePassword(boolean mustChangePassword) { this.mustChangePassword = mustChangePassword; }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String token;
        private String tokenType = "Bearer";
        private String username;
        private Long userId;
        private String firstName;
        private String lastName;
        private boolean mustChangePassword;

        public Builder token(String token) { this.token = token; return this; }
        public Builder tokenType(String tokenType) { this.tokenType = tokenType; return this; }
        public Builder username(String username) { this.username = username; return this; }
        public Builder userId(Long userId) { this.userId = userId; return this; }
        public Builder firstName(String firstName) { this.firstName = firstName; return this; }
        public Builder lastName(String lastName) { this.lastName = lastName; return this; }
        public Builder mustChangePassword(boolean mustChangePassword) { this.mustChangePassword = mustChangePassword; return this; }
        public JwtAuthResponse build() {
            return new JwtAuthResponse(token, username, userId, firstName, lastName, mustChangePassword);
        }
    }
}
