package com.miniurl;

import com.miniurl.exception.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Exception Tests")
class ExceptionTest {

    @Test
    @DisplayName("AliasNotAvailableException")
    void aliasNotAvailableException() {
        AliasNotAvailableException ex = new AliasNotAvailableException("Alias 'test' is already taken");
        assertEquals("Alias 'test' is already taken", ex.getMessage());
        assertInstanceOf(RuntimeException.class, ex);
    }

    @Test
    @DisplayName("RateLimitCooldownException")
    void rateLimitCooldownException() {
        RateLimitCooldownException ex = new RateLimitCooldownException("Please wait 30 seconds before requesting another OTP");
        assertEquals("Please wait 30 seconds before requesting another OTP", ex.getMessage());
        assertInstanceOf(RuntimeException.class, ex);
    }

    @Test
    @DisplayName("ResourceNotFoundException")
    void resourceNotFoundException() {
        ResourceNotFoundException ex = new ResourceNotFoundException("User not found with id: 42");
        assertEquals("User not found with id: 42", ex.getMessage());
        assertInstanceOf(RuntimeException.class, ex);
    }

    @Test
    @DisplayName("UnauthorizedException")
    void unauthorizedException() {
        UnauthorizedException ex = new UnauthorizedException("Invalid credentials");
        assertEquals("Invalid credentials", ex.getMessage());
        assertInstanceOf(RuntimeException.class, ex);
    }

    @Test
    @DisplayName("UrlValidationException")
    void urlValidationException() {
        UrlValidationException ex = new UrlValidationException("URL must not contain spaces");
        assertEquals("URL must not contain spaces", ex.getMessage());
        assertInstanceOf(RuntimeException.class, ex);
    }

    @Test
    @DisplayName("UrlLimitExceededException - per minute")
    void urlLimitExceededExceptionPerMinute() {
        UrlLimitExceededException ex = new UrlLimitExceededException("per minute", 10, 10);
        assertTrue(ex.getMessage().contains("10 URLs per minute"));
        assertTrue(ex.getMessage().contains("Please try again after"));
        assertEquals("per minute", ex.getLimitType());
        assertEquals(10, ex.getLimit());
        assertEquals(10, ex.getCurrentCount());
        assertNotNull(ex.getRetryAfter());
        assertNotNull(ex.getRetryMessage());
        assertNotNull(ex.getUiMessage());
    }

    @Test
    @DisplayName("UrlLimitExceededException - per day")
    void urlLimitExceededExceptionPerDay() {
        UrlLimitExceededException ex = new UrlLimitExceededException("per day", 50, 50);
        assertTrue(ex.getMessage().contains("50 URLs per day"));
        assertTrue(ex.getMessage().contains("tomorrow"));
        assertEquals("per day", ex.getLimitType());
        assertEquals(50, ex.getLimit());
        assertEquals(50, ex.getCurrentCount());
        assertNotNull(ex.getRetryAfter());
    }

    @Test
    @DisplayName("UrlLimitExceededException - per month")
    void urlLimitExceededExceptionPerMonth() {
        UrlLimitExceededException ex = new UrlLimitExceededException("per month", 1000, 1000);
        assertTrue(ex.getMessage().contains("1000 URLs per month"));
        assertTrue(ex.getMessage().contains("1st of"));
        assertEquals("per month", ex.getLimitType());
        assertEquals(1000, ex.getLimit());
        assertEquals(1000, ex.getCurrentCount());
        assertNotNull(ex.getRetryAfter());
    }

    @Test
    @DisplayName("UrlLimitExceededException - unknown limit type (default)")
    void urlLimitExceededExceptionDefault() {
        UrlLimitExceededException ex = new UrlLimitExceededException("unknown", 5, 3);
        assertTrue(ex.getMessage().contains("unknown"));
        assertEquals("unknown", ex.getLimitType());
        assertEquals(5, ex.getLimit());
        assertEquals(3, ex.getCurrentCount());
    }
}
