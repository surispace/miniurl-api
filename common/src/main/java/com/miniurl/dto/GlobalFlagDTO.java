package com.miniurl.dto;

import com.miniurl.entity.Feature;
import com.miniurl.entity.GlobalFlag;

import java.time.LocalDateTime;

/**
 * Data Transfer Object for GlobalFlag.
 * Includes feature metadata and global enabled status.
 */
public class GlobalFlagDTO {

    private Long id;
    private Long featureId;
    private String featureKey;
    private String featureName;
    private String description;
    private boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * Default constructor
     */
    public GlobalFlagDTO() {
    }

    /**
     * Constructor from entity
     */
    public GlobalFlagDTO(GlobalFlag globalFlag) {
        this.id = globalFlag.getId();
        Feature feature = globalFlag.getFeature();
        if (feature != null) {
            this.featureId = feature.getId();
            this.featureKey = feature.getFeatureKey();
            this.featureName = feature.getFeatureName();
            this.description = feature.getDescription();
        }
        this.enabled = globalFlag.isEnabled();
        this.createdAt = globalFlag.getCreatedAt();
        this.updatedAt = globalFlag.getUpdatedAt();
    }

    /**
     * Full constructor
     */
    public GlobalFlagDTO(Long id, Long featureId, String featureKey, String featureName, String description,
                         boolean enabled, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.featureId = featureId;
        this.featureKey = featureKey;
        this.featureName = featureName;
        this.description = description;
        this.enabled = enabled;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    // Getters and Setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getFeatureId() {
        return featureId;
    }

    public void setFeatureId(Long featureId) {
        this.featureId = featureId;
    }

    public String getFeatureKey() {
        return featureKey;
    }

    public void setFeatureKey(String featureKey) {
        this.featureKey = featureKey;
    }

    public String getFeatureName() {
        return featureName;
    }

    public void setFeatureName(String featureName) {
        this.featureName = featureName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public String toString() {
        return "GlobalFlagDTO{" +
                "id=" + id +
                ", featureKey='" + featureKey + '\'' +
                ", featureName='" + featureName + '\'' +
                ", enabled=" + enabled +
                '}';
    }
}
