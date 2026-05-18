package com.miniurl.url.service;

/**
 * Interface for tracking URL creations per minute per user.
 */
public interface UrlCreationTracker {

    /**
     * Increment the URL creation count for a user in the current minute
     */
    void increment(Long userId);

    /**
     * Get the count of URL creations in the last minute for a user
     */
    int getCountForLastMinute(Long userId);

    /**
     * Cleanup old entries to prevent memory leaks
     */
    void cleanupOldEntries();
}
