package com.miniurl.feature.repository;

import com.miniurl.entity.Feature;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface FeatureRepository extends JpaRepository<Feature, Long> {
    Optional<Feature> findByFeatureKey(String featureKey);
    boolean existsByFeatureKey(String featureKey);
}
