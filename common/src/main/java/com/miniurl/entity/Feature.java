package com.miniurl.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

/**
 * Entity representing a master feature definition.
 * Contains feature metadata (key, name, description).
 * Role-based enabled status is stored in FeatureFlag entity.
 */
@Entity
@Table(name = "features")
public class Feature {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Feature key is required")
    @Size(max = 100, message = "Feature key must be 100 characters or less")
    @Column(name = "feature_key", unique = true, nullable = false, length = 100)
    private String featureKey;

    @NotBlank(message = "Feature name is required")
    @Size(max = 255, message = "Feature name must be 255 characters or less")
    @Column(name = "feature_name", nullable = false, length = 255)
    private String featureName;

    @Size(max = 1000, message = "Description must be 1000 characters or less")
    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Default constructor
     */
    public Feature() {
    }

    /**
     * Constructor with required fields
     */
    public Feature(String featureKey, String featureName, String description) {
        this.featureKey = featureKey;
        this.featureName = featureName;
        this.description = description;
    }

    /**
     * Pre-persist callback to set timestamps
     */
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    // Getters and Setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public String toString() {
        return "Feature{" +
                "id=" + id +
                ", featureKey='" + featureKey + '\'' +
                ", featureName='" + featureName + '\'' +
                '}';
    }
}
