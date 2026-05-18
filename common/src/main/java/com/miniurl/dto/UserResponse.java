package com.miniurl.dto;

import java.time.LocalDateTime;

public class UserResponse {
    private Long id;
    private String firstName;
    private String lastName;
    private String email;
    private String username;
    private String roleName;
    private LocalDateTime createdAt;
    private LocalDateTime lastLogin;
    private String status;

    public UserResponse() {}

    public UserResponse(Long id, String firstName, String lastName, String email, String username, 
                       String roleName, LocalDateTime createdAt, LocalDateTime lastLogin, String status) {
        this.id = id;
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.username = username;
        this.roleName = roleName;
        this.createdAt = createdAt;
        this.lastLogin = lastLogin;
        this.status = status;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getRoleName() { return roleName; }
    public void setRoleName(String roleName) { this.roleName = roleName; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getLastLogin() { return lastLogin; }
    public void setLastLogin(LocalDateTime lastLogin) { this.lastLogin = lastLogin; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Long id;
        private String firstName;
        private String lastName;
        private String email;
        private String username;
        private String roleName;
        private LocalDateTime createdAt;
        private LocalDateTime lastLogin;
        private String status;

        public Builder id(Long id) { this.id = id; return this; }
        public Builder firstName(String firstName) { this.firstName = firstName; return this; }
        public Builder lastName(String lastName) { this.lastName = lastName; return this; }
        public Builder email(String email) { this.email = email; return this; }
        public Builder username(String username) { this.username = username; return this; }
        public Builder roleName(String roleName) { this.roleName = roleName; return this; }
        public Builder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public Builder lastLogin(LocalDateTime lastLogin) { this.lastLogin = lastLogin; return this; }
        public Builder status(String status) { this.status = status; return this; }
        public UserResponse build() { 
            return new UserResponse(id, firstName, lastName, email, username, roleName, createdAt, lastLogin, status); 
        }
    }
}
