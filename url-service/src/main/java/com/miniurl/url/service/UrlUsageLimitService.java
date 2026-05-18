package com.miniurl.url.service;

import com.miniurl.exception.UrlLimitExceededException;
import com.miniurl.url.repository.UrlRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Service for managing URL creation rate limits per user:
 * - 10 URLs per minute
 * - 100 URLs per day
 * - 1000 URLs per month (resets on 1st of every month)
 * 
 * Limits are enforced by counting actual URLs from the database.
 */
@Service
public class UrlUsageLimitService {

    // Rate limit constants
    public static final int MINUTE_LIMIT = 10;
    public static final int DAILY_LIMIT = 100;
    public static final int MONTHLY_LIMIT = 1000;

    private final UrlRepository urlRepository;
    private final UrlCreationTracker minuteTracker;

    public UrlUsageLimitService(UrlRepository urlRepository,
                                UrlCreationTracker minuteTracker) {
        this.urlRepository = urlRepository;
        this.minuteTracker = minuteTracker;
    }

    /**
     * Check if user can create a new URL and increment counters if allowed.
     * Throws UrlLimitExceededException if any limit is reached.
     * 
     * Check order (cascading):
     * 1. Monthly limit (if reached: all 3 cards red, monthly message)
     * 2. Daily limit (if reached: minute + day cards red, daily message)
     * 3. Minute limit (if reached: minute card red, minute message)
     * 
     * Counts actual URLs from database for accurate limit enforcement.
     */
    @Transactional
    public void checkAndIncrementUrlCreation(Long userId) {
        int currentYear = LocalDate.now().getYear();
        int currentMonth = LocalDate.now().getMonthValue();
        LocalDate today = LocalDate.now();

        // Count actual URLs from database for accurate limit checking
        int monthlyCount = urlRepository.countByUserIdAndMonth(userId, currentYear, currentMonth);
        int dailyCount = urlRepository.countByUserIdAndDay(userId, today);
        int minuteCount = minuteTracker.getCountForLastMinute(userId);

        // Check limits in cascading order:
        
        // 1. Check monthly limit FIRST (highest priority)
        // If monthly limit reached: all 3 cards red, monthly message
        if (monthlyCount >= MONTHLY_LIMIT) {
            throw new UrlLimitExceededException("per month", MONTHLY_LIMIT, monthlyCount);
        }

        // 2. Check daily limit SECOND
        // If daily limit reached: minute + day cards red, daily message
        if (dailyCount >= DAILY_LIMIT) {
            throw new UrlLimitExceededException("per day", DAILY_LIMIT, dailyCount);
        }

        // 3. Check minute limit THIRD (lowest priority)
        // If minute limit reached: minute card red, minute message
        if (minuteCount >= MINUTE_LIMIT) {
            throw new UrlLimitExceededException("per minute", MINUTE_LIMIT, minuteCount);
        }

        // All checks passed, increment minute tracker
        minuteTracker.increment(userId);
    }

    /**
     * Get current usage statistics for a user
     * Counts actual URLs from database for accurate display
     */
    @Transactional(readOnly = true)
    public UrlUsageStats getUsageStats(Long userId) {
        int currentYear = LocalDate.now().getYear();
        int currentMonth = LocalDate.now().getMonthValue();
        LocalDate today = LocalDate.now();

        // Count actual URLs from database for accurate stats
        int monthlyCount = urlRepository.countByUserIdAndMonth(userId, currentYear, currentMonth);
        int dailyCount = urlRepository.countByUserIdAndDay(userId, today);
        int minuteCount = minuteTracker.getCountForLastMinute(userId);

        return new UrlUsageStats(
                minuteCount,
                MINUTE_LIMIT,
                dailyCount,
                DAILY_LIMIT,
                monthlyCount,
                MONTHLY_LIMIT
        );
    }

    /**
     * DTO for URL usage statistics
     */
    public static class UrlUsageStats {
        private final int minuteCount;
        private final int minuteLimit;
        private final int dailyCount;
        private final int dailyLimit;
        private final int monthlyCount;
        private final int monthlyLimit;

        public UrlUsageStats(int minuteCount, int minuteLimit,
                           int dailyCount, int dailyLimit,
                           int monthlyCount, int monthlyLimit) {
            this.minuteCount = minuteCount;
            this.minuteLimit = minuteLimit;
            this.dailyCount = dailyCount;
            this.dailyLimit = dailyLimit;
            this.monthlyCount = monthlyCount;
            this.monthlyLimit = monthlyLimit;
        }

        public int getMinuteCount() { return minuteCount; }
        public int getMinuteLimit() { return minuteLimit; }
        public int getDailyCount() { return dailyCount; }
        public int getDailyLimit() { return dailyLimit; }
        public int getMonthlyCount() { return monthlyCount; }
        public int getMonthlyLimit() { return monthlyLimit; }

        public int getMinuteRemaining() { return Math.max(0, minuteLimit - minuteCount); }
        public int getDailyRemaining() { return Math.max(0, dailyLimit - dailyCount); }
        public int getMonthlyRemaining() { return Math.max(0, monthlyLimit - monthlyCount); }
    }
}
