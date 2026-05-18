package com.miniurl.dto;

import com.miniurl.entity.Feature;
import com.miniurl.entity.FeatureFlag;

import java.time.LocalDateTime;

/**
 * Data Transfer Object for FeatureFlag.
 * Includes feature metadata and role-based enabled status.
 */
public class FeatureFlagDTO {

    private Long id;
    private Long featureId;
    private String featureKey;
    private String featureName;
    private String description;
    private boolean enabled;
    private Long roleId;
    private String roleName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * Default constructor
     */
    public FeatureFlagDTO() {
    }

    /**
     * Constructor from entity
     */
    public FeatureFlagDTO(FeatureFlag featureFlag) {
        this.id = featureFlag.getId();
        Feature feature = featureFlag.getFeature();
        if (feature != null) {
            this.featureId = feature.getId();
            this.featureKey = feature.getFeatureKey();
            this.featureName = feature.getFeatureName();
            this.description = feature.getDescription();
        }
        this.enabled = featureFlag.isEnabled();
        if (featureFlag.getRole() != null) {
            this.roleId = featureFlag.getRole().getId();
            this.roleName = featureFlag.getRole().getName();
        }
        this.createdAt = featureFlag.getCreatedAt();
        this.updatedAt = featureFlag.getUpdatedAt();
    }

    /**
     * Full constructor
     */
    public FeatureFlagDTO(Long id, Long featureId, String featureKey, String featureName, String description,
                          boolean enabled, Long roleId, String roleName, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.featureId = featureId;
        this.featureKey = featureKey;
        this.featureName = featureName;
        this.description = description;
        this.enabled = enabled;
        this.roleId = roleId;
        this.roleName = roleName;
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

    public Long getRoleId() {
        return roleId;
    }

    public void setRoleId(Long roleId) {
        this.roleId = roleId;
    }

    public String getRoleName() {
        return roleName;
    }

    public void setRoleName(String roleName) {
        this.roleName = roleName;
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
        return "FeatureFlagDTO{" +
                "id=" + id +
                ", featureKey='" + featureKey + '\'' +
                ", featureName='" + featureName + '\'' +
                ", enabled=" + enabled +
                ", roleName=" + roleName +
                '}';
    }
}
