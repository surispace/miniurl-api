package com.miniurl.identity.exception;

public class RateLimitCooldownException extends RuntimeException {
    public RateLimitCooldownException(String message) {
        super(message);
    }
}