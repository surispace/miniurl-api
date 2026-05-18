package com.miniurl.exception;

/**
 * Thrown when a rate limit cooldown is violated (e.g., OTP resend too soon).
 * Returns HTTP 429 (Too Many Requests) or 400 (Bad Request) depending on context.
 */
public class RateLimitCooldownException extends RuntimeException {
    public RateLimitCooldownException(String message) {
        super(message);
    }
}
