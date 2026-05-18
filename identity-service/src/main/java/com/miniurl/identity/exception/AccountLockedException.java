package com.miniurl.identity.exception;

/**
 * Thrown when an account is temporarily locked due to too many failed login attempts.
 * Should result in HTTP 423 (Locked).
 */
public class AccountLockedException extends RuntimeException {
    private final long remainingSeconds;

    public AccountLockedException(String message, long remainingSeconds) {
        super(message);
        this.remainingSeconds = remainingSeconds;
    }

    public long getRemainingSeconds() {
        return remainingSeconds;
    }
}
