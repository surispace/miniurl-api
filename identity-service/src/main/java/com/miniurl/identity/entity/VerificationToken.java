package com.miniurl.identity.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

@Entity
@Table(name = "verification_tokens")
public class VerificationToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @NotBlank(message = "Token is required")
    @Size(max = 255, message = "Token must be 255 characters or less")
    @Column(nullable = false, unique = true, length = 255)
    private String token;

    @NotBlank(message = "Token type is required")
    @Size(max = 50, message = "Token type must be 50 characters or less")
    @Column(name = "token_type", nullable = false, length = 50)
    private String tokenType; // EMAIL_VERIFICATION, PASSWORD_RESET

    @Column(name = "expiry_time", nullable = false)
    private LocalDateTime expiryTime;

    @Column(nullable = false)
    private boolean used = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public VerificationToken() {}

    public VerificationToken(User user, String token, String tokenType, LocalDateTime expiryTime) {
        this.user = user;
        this.token = token;
        this.tokenType = tokenType;
        this.expiryTime = expiryTime;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public String getTokenType() { return tokenType; }
    public void setTokenType(String tokenType) { this.tokenType = tokenType; }

    public LocalDateTime getExpiryTime() { return expiryTime; }
    public void setExpiryTime(LocalDateTime expiryTime) { this.expiryTime = expiryTime; }

    public boolean isUsed() { return used; }
    public void setUsed(boolean used) { this.used = used; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiryTime);
    }

    public boolean isValid() {
        return !used && !isExpired();
    }

    public static final String TYPE_EMAIL_VERIFICATION = "EMAIL_VERIFICATION";
    public static final String TYPE_PASSWORD_RESET = "PASSWORD_RESET";

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Long id;
        private User user;
        private String token;
        private String tokenType;
        private LocalDateTime expiryTime;
        private boolean used = false;

        public Builder id(Long id) { this.id = id; return this; }
        public Builder user(User user) { this.user = user; return this; }
        public Builder token(String token) { this.token = token; return this; }
        public Builder tokenType(String tokenType) { this.tokenType = tokenType; return this; }
        public Builder expiryTime(LocalDateTime expiryTime) { this.expiryTime = expiryTime; return this; }
        public Builder used(boolean used) { this.used = used; return this; }
        public VerificationToken build() { return new VerificationToken(user, token, tokenType, expiryTime); }
    }
}
