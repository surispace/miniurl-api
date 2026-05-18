package com.miniurl.url.repository;

import com.miniurl.url.entity.UrlUsageLimit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UrlUsageLimitRepository extends JpaRepository<UrlUsageLimit, Long> {

    /**
     * Find usage limit record for a user in a specific month
     */
    Optional<UrlUsageLimit> findByUserIdAndPeriodYearAndPeriodMonth(
            @Param("userId") Long userId,
            @Param("periodYear") int periodYear,
            @Param("periodMonth") int periodMonth);

    /**
     * Check if a usage record exists for the user in a specific month
     */
    boolean existsByUserIdAndPeriodYearAndPeriodMonth(
            @Param("userId") Long userId,
            @Param("periodYear") int periodYear,
            @Param("periodMonth") int periodMonth);
}
