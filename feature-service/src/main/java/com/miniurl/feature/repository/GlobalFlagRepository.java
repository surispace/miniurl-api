package com.miniurl.feature.repository;

import com.miniurl.entity.GlobalFlag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface GlobalFlagRepository extends JpaRepository<GlobalFlag, Long> {
    Optional<GlobalFlag> findByFeatureId(Long featureId);

    @Query("SELECT gf FROM GlobalFlag gf JOIN gf.feature f WHERE f.featureKey = :featureKey")
    Optional<GlobalFlag> findByFeatureKey(@Param("featureKey") String featureKey);

    @Query("SELECT COUNT(gf) > 0 FROM GlobalFlag gf JOIN gf.feature f WHERE f.featureKey = :featureKey")
    boolean existsByFeatureKey(@Param("featureKey") String featureKey);
}
