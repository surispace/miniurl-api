package com.miniurl.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Entity representing a feature flag for a specific role.
 * Contains role-based enabled status linked to a master feature.
 */
@Entity
@Table(name = "feature_flags")
public class FeatureFlag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "feature_id", nullable = false, foreignKey = @ForeignKey(name = "fk_feature_flags_feature"))
    private Feature feature;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "role_id", nullable = false, foreignKey = @ForeignKey(name = "fk_feature_flags_role"))
    private Role role;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * Default constructor
     */
    public FeatureFlag() {
    }

    /**
     * Constructor with required fields
     */
    public FeatureFlag(Feature feature, Role role, boolean enabled) {
        this.feature = feature;
        this.role = role;
        this.enabled = enabled;
    }

    /**
     * Pre-persist callback to set timestamps
     */
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    /**
     * Pre-update callback to update timestamp
     */
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // Getters and Setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Feature getFeature() {
        return feature;
    }

    public void setFeature(Feature feature) {
        this.feature = feature;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
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

    /**
     * Toggle the enabled state
     */
    public void toggle() {
        this.enabled = !this.enabled;
    }

    @Override
    public String toString() {
        return "FeatureFlag{" +
                "id=" + id +
                ", feature=" + (feature != null ? feature.getFeatureKey() : "null") +
                ", role=" + (role != null ? role.getName() : "null") +
                ", enabled=" + enabled +
                '}';
    }
}
