package com.miniurl.url.repository;

import com.miniurl.url.entity.Url;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface UrlRepository extends JpaRepository<Url, Long> {
    Optional<Url> findByShortCode(String shortCode);
    boolean existsByShortCode(String shortCode);
    List<Url> findByUserId(Long userId);
    Optional<Url> findByIdAndUserId(Long id, Long userId);
    Page<Url> findByUserId(Long userId, Pageable pageable);
    
    /**
     * Count URLs created for a user in a specific month and year
     */
    @Query("SELECT COUNT(u) FROM Url u WHERE u.userId = :userId AND YEAR(u.createdAt) = :year AND MONTH(u.createdAt) = :month")
    int countByUserIdAndMonth(@Param("userId") Long userId, @Param("year") int year, @Param("month") int month);
    
    /**
     * Count URLs created for a user on a specific day
     */
    @Query("SELECT COUNT(u) FROM Url u WHERE u.userId = :userId AND DATE(u.createdAt) = :day")
    int countByUserIdAndDay(@Param("userId") Long userId, @Param("day") LocalDate day);
}
