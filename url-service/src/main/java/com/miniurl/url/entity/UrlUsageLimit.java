package com.miniurl.url.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Tracks URL creation usage per user for rate limiting.
 * - 10 URLs per minute
 * - 100 URLs per day
 * - 1000 URLs per month (resets on 1st of every month)
 */
@Entity
@Table(name = "url_usage_limits",
       indexes = {
           @Index(name = "idx_user_id", columnList = "userId"),
           @Index(name = "idx_period", columnList = "periodYear,periodMonth")
       },
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "period_year", "period_month"}))
public class UrlUsageLimit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "period_year", nullable = false)
    private int periodYear;

    @Column(name = "period_month", nullable = false)
    private int periodMonth;

    @Column(name = "daily_count", nullable = false)
    private int dailyCount = 0;

    @Column(name = "monthly_count", nullable = false)
    private int monthlyCount = 0;

    @Column(name = "last_reset_date")
    private LocalDateTime lastResetDate;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public UrlUsageLimit() {}

    public UrlUsageLimit(Long userId, int periodYear, int periodMonth) {
        this.userId = userId;
        this.periodYear = periodYear;
        this.periodMonth = periodMonth;
        this.dailyCount = 0;
        this.monthlyCount = 0;
        this.lastResetDate = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * Reset daily count for a new day
     */
    public void resetDailyCount() {
        this.dailyCount = 0;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Reset monthly count for a new month
     */
    public void resetMonthlyCount() {
        this.monthlyCount = 0;
        this.dailyCount = 0;
        this.lastResetDate = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Increment counters
     */
    public void increment() {
        this.dailyCount++;
        this.monthlyCount++;
        this.updatedAt = LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public int getPeriodYear() { return periodYear; }
    public void setPeriodYear(int periodYear) { this.periodYear = periodYear; }

    public int getPeriodMonth() { return periodMonth; }
    public void setPeriodMonth(int periodMonth) { this.periodMonth = periodMonth; }

    public int getDailyCount() { return dailyCount; }
    public void setDailyCount(int dailyCount) { this.dailyCount = dailyCount; }

    public int getMonthlyCount() { return monthlyCount; }
    public void setMonthlyCount(int monthlyCount) { this.monthlyCount = monthlyCount; }

    public LocalDateTime getLastResetDate() { return lastResetDate; }
    public void setLastResetDate(LocalDateTime lastResetDate) { this.lastResetDate = lastResetDate; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Long id;
        private Long userId;
        private int periodYear;
        private int periodMonth;
        private int dailyCount = 0;
        private int monthlyCount = 0;
        private LocalDateTime lastResetDate;
        private LocalDateTime updatedAt;

        public Builder id(Long id) { this.id = id; return this; }
        public Builder userId(Long userId) { this.userId = userId; return this; }
        public Builder periodYear(int periodYear) { this.periodYear = periodYear; return this; }
        public Builder periodMonth(int periodMonth) { this.periodMonth = periodMonth; return this; }
        public Builder dailyCount(int dailyCount) { this.dailyCount = dailyCount; return this; }
        public Builder monthlyCount(int monthlyCount) { this.monthlyCount = monthlyCount; return this; }
        public Builder lastResetDate(LocalDateTime lastResetDate) { this.lastResetDate = lastResetDate; return this; }
        public Builder updatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; return this; }
        public UrlUsageLimit build() {
            UrlUsageLimit limit = new UrlUsageLimit(userId, periodYear, periodMonth);
            limit.id = id;
            limit.dailyCount = dailyCount;
            limit.monthlyCount = monthlyCount;
            limit.lastResetDate = lastResetDate;
            limit.updatedAt = updatedAt;
            return limit;
        }
    }
}
