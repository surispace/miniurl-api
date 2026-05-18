package com.miniurl.feature.repository;

import com.miniurl.entity.FeatureFlag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FeatureFlagRepository extends JpaRepository<FeatureFlag, Long> {
    Optional<FeatureFlag> findByFeatureIdAndRoleId(Long featureId, Long roleId);

    List<FeatureFlag> findByRoleIdOrderByFeatureFeatureNameAsc(Long roleId);

    @Query("SELECT ff FROM FeatureFlag ff JOIN ff.feature f WHERE f.featureKey = :featureKey AND ff.role.id = :roleId")
    Optional<FeatureFlag> findByFeatureKeyAndRoleId(@Param("featureKey") String featureKey, @Param("roleId") Long roleId);

    @Query("SELECT ff FROM FeatureFlag ff JOIN FETCH ff.feature ORDER BY ff.feature.featureName ASC")
    List<FeatureFlag> findAllWithFeatures();
}
