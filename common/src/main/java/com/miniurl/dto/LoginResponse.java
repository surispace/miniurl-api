package com.miniurl.dto;

public class LoginResponse {
    private String token;
    private String username;
    private Long userId;
    private boolean mustChangePassword;
    private String firstName;
    private String lastName;

    public LoginResponse() {}

    public LoginResponse(String token, String username, Long userId, boolean mustChangePassword, String firstName, String lastName) {
        this.token = token;
        this.username = username;
        this.userId = userId;
        this.mustChangePassword = mustChangePassword;
        this.firstName = firstName;
        this.lastName = lastName;
    }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public boolean isMustChangePassword() { return mustChangePassword; }
    public void setMustChangePassword(boolean mustChangePassword) { this.mustChangePassword = mustChangePassword; }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String token;
        private String username;
        private Long userId;
        private boolean mustChangePassword;
        private String firstName;
        private String lastName;

        public Builder token(String token) { this.token = token; return this; }
        public Builder username(String username) { this.username = username; return this; }
        public Builder userId(Long userId) { this.userId = userId; return this; }
        public Builder mustChangePassword(boolean mustChangePassword) { this.mustChangePassword = mustChangePassword; return this; }
        public Builder firstName(String firstName) { this.firstName = firstName; return this; }
        public Builder lastName(String lastName) { this.lastName = lastName; return this; }
        public LoginResponse build() { return new LoginResponse(token, username, userId, mustChangePassword, firstName, lastName); }
    }
}
