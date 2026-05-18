package com.miniurl.url.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory tracker for URL creations per minute per user.
 * Uses a sliding window approach with automatic cleanup.
 */
@Component
public class UrlCreationMinuteTracker implements UrlCreationTracker {

    // Map of userId -> (minuteTimestamp -> count)
    private final Map<Long, Map<Long, AtomicInteger>> userMinuteCounts = new ConcurrentHashMap<>();

    /**
     * Increment the URL creation count for a user in the current minute
     */
    @Override
    public void increment(Long userId) {
        long currentMinute = getCurrentMinuteTimestamp();
        
        Map<Long, AtomicInteger> minuteMap = userMinuteCounts.computeIfAbsent(userId, k -> new ConcurrentHashMap<>());
        AtomicInteger count = minuteMap.computeIfAbsent(currentMinute, k -> new AtomicInteger(0));
        count.incrementAndGet();
    }

    /**
     * Get the count of URL creations in the last minute for a user
     */
    @Override
    public int getCountForLastMinute(Long userId) {
        long currentMinute = getCurrentMinuteTimestamp();
        
        Map<Long, AtomicInteger> minuteMap = userMinuteCounts.get(userId);
        if (minuteMap == null) {
            return 0;
        }

        AtomicInteger count = minuteMap.get(currentMinute);
        return count != null ? count.get() : 0;
    }

    /**
     * Get the current minute timestamp (seconds since epoch, rounded to minute)
     */
    private long getCurrentMinuteTimestamp() {
        return Instant.now().getEpochSecond() / 60;
    }

    /**
     * Cleanup old entries (older than 2 minutes) to prevent memory leaks
     * Runs every 5 minutes
     */
    @Scheduled(fixedRate = 300000) // 5 minutes
    @Override
    public void cleanupOldEntries() {
        long currentMinute = getCurrentMinuteTimestamp();
        long cutoffMinute = currentMinute - 2; // Keep only last 2 minutes

        userMinuteCounts.forEach((userId, minuteMap) -> {
            minuteMap.keySet().removeIf(minute -> minute < cutoffMinute);
        });

        // Remove empty user maps
        userMinuteCounts.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }
}
