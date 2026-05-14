package com.miniurl.identity.entity;

import com.miniurl.enums.Theme;
import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Size(max = 100, message = "First name must be 100 characters or less")
    @Column(nullable = false, length = 100)
    private String firstName;

    @Size(max = 100, message = "Last name must be 100 characters or less")
    @Column(nullable = false, length = 100)
    private String lastName;

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be valid")
    @Size(max = 255, message = "Email must be 255 characters or less")
    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @NotBlank(message = "Username is required")
    @Size(max = 255, message = "Username must be 255 characters or less")
    @Column(nullable = false, unique = true, length = 255)
    private String username;

    @NotBlank(message = "Password is required")
    @Column(nullable = false)
    private String password;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    @Column(name = "must_change_password")
    private boolean mustChangePassword = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "last_login")
    private LocalDateTime lastLogin;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "token_version", nullable = false)
    private int tokenVersion = 0;

    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts = 0;

    @Column(name = "lockout_time")
    private LocalDateTime lockoutTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "theme", nullable = false, length = 20)
    private Theme theme = Theme.LIGHT;

    public User() {}

    public User(String firstName, String lastName, String email, String username, String password) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.username = username;
        this.password = password;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) {
            status = UserStatus.ACTIVE;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public String getFullName() {
        return firstName + " " + lastName;
    }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }

    public boolean isMustChangePassword() { return mustChangePassword; }
    public void setMustChangePassword(boolean mustChangePassword) { this.mustChangePassword = mustChangePassword; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public LocalDateTime getLastLogin() { return lastLogin; }
    public void setLastLogin(LocalDateTime lastLogin) { this.lastLogin = lastLogin; }

    public UserStatus getStatus() { return status; }
    public void setStatus(UserStatus status) { this.status = status; }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    public boolean isAdmin() {
        return role != null && "ADMIN".equals(role.getName());
    }

    public int getTokenVersion() { return tokenVersion; }
    public void setTokenVersion(int tokenVersion) { this.tokenVersion = tokenVersion; }

    public int getFailedLoginAttempts() { return failedLoginAttempts; }
    public void setFailedLoginAttempts(int failedLoginAttempts) { this.failedLoginAttempts = failedLoginAttempts; }

    public LocalDateTime getLockoutTime() { return lockoutTime; }
    public void setLockoutTime(LocalDateTime lockoutTime) { this.lockoutTime = lockoutTime; }

    public Theme getTheme() { return theme; }
    public void setTheme(Theme theme) { this.theme = theme; }

    /**
     * Check if account is locked
     */
    public boolean isAccountLocked() {
        return lockoutTime != null && lockoutTime.isAfter(LocalDateTime.now());
    }

    /**
     * Check if account was locked but lockout period has expired
     */
    public boolean isLockoutExpired() {
        return lockoutTime != null && lockoutTime.isBefore(LocalDateTime.now());
    }

    /**
     * Check if the account is eligible for a login attempt.
     * Returns false if the account is currently locked.
     */
    public boolean isLoginAttemptAllowed() {
        return !isAccountLocked();
    }

    /**
     * Increment failed login attempts and lock if threshold reached
     * Locks account for 5 minutes after 5 failed attempts
     */
    public void incrementFailedLoginAttempts() {
        this.failedLoginAttempts++;
        if (this.failedLoginAttempts >= 5) {
            this.lockoutTime = LocalDateTime.now().plusMinutes(5);
        }
    }

    /**
     * Reset failed login attempts on successful login
     */
    public void resetFailedLoginAttempts() {
        this.failedLoginAttempts = 0;
        this.lockoutTime = null;
    }

    /**
     * Increment token version to invalidate all existing tokens
     */
    public void incrementTokenVersion() {
        this.tokenVersion++;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Long id;
        private String firstName;
        private String lastName;
        private String email;
        private String username;
        private String password;
        private Role role;
        private boolean mustChangePassword = false;
        private LocalDateTime lastLogin;
        private UserStatus status = UserStatus.ACTIVE;
        private int tokenVersion = 0;
        private int failedLoginAttempts = 0;
        private LocalDateTime lockoutTime;
        private Theme theme = Theme.LIGHT;

        public Builder id(Long id) { this.id = id; return this; }
        public Builder firstName(String firstName) { this.firstName = firstName; return this; }
        public Builder lastName(String lastName) { this.lastName = lastName; return this; }
        public Builder email(String email) { this.email = email; return this; }
        public Builder username(String username) { this.username = username; return this; }
        public Builder password(String password) { this.password = password; return this; }
        public Builder role(Role role) { this.role = role; return this; }
        public Builder mustChangePassword(boolean mustChangePassword) { this.mustChangePassword = mustChangePassword; return this; }
        public Builder lastLogin(LocalDateTime lastLogin) { this.lastLogin = lastLogin; return this; }
        public Builder status(UserStatus status) { this.status = status; return this; }
        public Builder tokenVersion(int tokenVersion) { this.tokenVersion = tokenVersion; return this; }
        public Builder failedLoginAttempts(int failedLoginAttempts) { this.failedLoginAttempts = failedLoginAttempts; return this; }
        public Builder lockoutTime(LocalDateTime lockoutTime) { this.lockoutTime = lockoutTime; return this; }
        public Builder theme(Theme theme) { this.theme = theme; return this; }
        public User build() {
            User user = new User(firstName, lastName, email, username, password);
            user.id = id;
            user.role = role;
            user.mustChangePassword = mustChangePassword;
            user.lastLogin = lastLogin;
            user.status = status;
            user.tokenVersion = tokenVersion;
            user.failedLoginAttempts = failedLoginAttempts;
            user.lockoutTime = lockoutTime;
            user.theme = theme;
            return user;
        }
    }
}
