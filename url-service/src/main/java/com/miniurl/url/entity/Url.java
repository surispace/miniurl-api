package com.miniurl.url.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

@Entity
@Table(name = "urls",
       indexes = @Index(name = "idx_short_code", columnList = "shortCode"),
       uniqueConstraints = @UniqueConstraint(columnNames = "short_code"))
public class Url {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Original URL is required")
    @Size(max = 2048, message = "Original URL must be 2048 characters or less")
    @Column(nullable = false, length = 2048)
    private String originalUrl;

    @NotBlank(message = "Short code is required")
    @Size(max = 10, message = "Short code must be 10 characters or less")
    @Column(nullable = false, length = 10)
    private String shortCode;
    
    @Column(name = "user_id", nullable = false)
    private Long userId;
    
    @Column(nullable = false)
    private LocalDateTime createdAt;
    
    @Column(nullable = false)
    private Long accessCount = 0L;
    
    public Url() {}
    
    public Url(Long id, String originalUrl, String shortCode, Long userId, LocalDateTime createdAt, Long accessCount) {
        this.id = id;
        this.originalUrl = originalUrl;
        this.shortCode = shortCode;
        this.userId = userId;
        this.createdAt = createdAt;
        this.accessCount = accessCount;
    }
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (accessCount == null) {
            accessCount = 0L;
        }
    }
    
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public String getOriginalUrl() { return originalUrl; }
    public void setOriginalUrl(String originalUrl) { this.originalUrl = originalUrl; }
    
    public String getShortCode() { return shortCode; }
    public void setShortCode(String shortCode) { this.shortCode = shortCode; }
    
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    
    public Long getAccessCount() { return accessCount; }
    public void setAccessCount(Long accessCount) { this.accessCount = accessCount; }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private Long id;
        private String originalUrl;
        private String shortCode;
        private Long userId;
        private LocalDateTime createdAt;
        private Long accessCount = 0L;
        
        public Builder id(Long id) { this.id = id; return this; }
        public Builder originalUrl(String originalUrl) { this.originalUrl = originalUrl; return this; }
        public Builder shortCode(String shortCode) { this.shortCode = shortCode; return this; }
        public Builder userId(Long userId) { this.userId = userId; return this; }
        public Builder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public Builder accessCount(Long accessCount) { this.accessCount = accessCount; return this; }
        public Url build() { return new Url(id, originalUrl, shortCode, userId, createdAt, accessCount); }
    }
}
