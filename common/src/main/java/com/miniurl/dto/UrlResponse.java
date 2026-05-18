package com.miniurl.dto;

import java.time.LocalDateTime;

public class UrlResponse {
    private Long id;
    private String originalUrl;
    private String shortCode;
    private String shortUrl;
    private Long accessCount;
    private LocalDateTime createdAt;
    
    public UrlResponse() {}
    
    public UrlResponse(Long id, String originalUrl, String shortCode, String shortUrl, Long accessCount, LocalDateTime createdAt) {
        this.id = id;
        this.originalUrl = originalUrl;
        this.shortCode = shortCode;
        this.shortUrl = shortUrl;
        this.accessCount = accessCount;
        this.createdAt = createdAt;
    }
    
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public String getOriginalUrl() { return originalUrl; }
    public void setOriginalUrl(String originalUrl) { this.originalUrl = originalUrl; }
    
    public String getShortCode() { return shortCode; }
    public void setShortCode(String shortCode) { this.shortCode = shortCode; }
    
    public String getShortUrl() { return shortUrl; }
    public void setShortUrl(String shortUrl) { this.shortUrl = shortUrl; }
    
    public Long getAccessCount() { return accessCount; }
    public void setAccessCount(Long accessCount) { this.accessCount = accessCount; }
    
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private Long id;
        private String originalUrl;
        private String shortCode;
        private String shortUrl;
        private Long accessCount;
        private LocalDateTime createdAt;
        
        public Builder id(Long id) { this.id = id; return this; }
        public Builder originalUrl(String originalUrl) { this.originalUrl = originalUrl; return this; }
        public Builder shortCode(String shortCode) { this.shortCode = shortCode; return this; }
        public Builder shortUrl(String shortUrl) { this.shortUrl = shortUrl; return this; }
        public Builder accessCount(Long accessCount) { this.accessCount = accessCount; return this; }
        public Builder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public UrlResponse build() { 
            return new UrlResponse(id, originalUrl, shortCode, shortUrl, accessCount, createdAt); 
        }
    }
}
