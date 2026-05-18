package com.miniurl;

import com.miniurl.util.ValidationUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ValidationUtils Tests")
class ValidationUtilsTest {

    @Test
    @DisplayName("isValidUsername - valid username")
    void isValidUsernameValid() {
        assertTrue(ValidationUtils.isValidUsername("john"));
        assertTrue(ValidationUtils.isValidUsername("john_doe"));
        assertTrue(ValidationUtils.isValidUsername("a1b2c3"));
        assertTrue(ValidationUtils.isValidUsername("abc"));
        assertTrue(ValidationUtils.isValidUsername("user_name_123"));
    }

    @Test
    @DisplayName("isValidUsername - invalid username")
    void isValidUsernameInvalid() {
        assertFalse(ValidationUtils.isValidUsername(null));
        assertFalse(ValidationUtils.isValidUsername(""));
        assertFalse(ValidationUtils.isValidUsername("ab"));
        assertFalse(ValidationUtils.isValidUsername("1abc"));
        assertFalse(ValidationUtils.isValidUsername("user@name"));
        assertFalse(ValidationUtils.isValidUsername("a"));
        assertFalse(ValidationUtils.isValidUsername("a".repeat(51)));
    }

    @Test
    @DisplayName("isReservedUsername - reserved usernames")
    void isReservedUsername() {
        assertTrue(ValidationUtils.isReservedUsername("admin"));
        assertTrue(ValidationUtils.isReservedUsername("root"));
        assertTrue(ValidationUtils.isReservedUsername("API"));
        assertTrue(ValidationUtils.isReservedUsername("Admin"));
        assertTrue(ValidationUtils.isReservedUsername("test"));
        assertFalse(ValidationUtils.isReservedUsername("john"));
        assertFalse(ValidationUtils.isReservedUsername(null));
        assertFalse(ValidationUtils.isReservedUsername("unique_user"));
    }

    @Test
    @DisplayName("isCommonPassword - common passwords")
    void isCommonPassword() {
        assertTrue(ValidationUtils.isCommonPassword("password"));
        assertTrue(ValidationUtils.isCommonPassword("123456"));
        assertTrue(ValidationUtils.isCommonPassword("admin123"));
        assertTrue(ValidationUtils.isCommonPassword("Password"));
        assertTrue(ValidationUtils.isCommonPassword("QWERTY"));
        assertFalse(ValidationUtils.isCommonPassword("MySecureP@ss1"));
        assertFalse(ValidationUtils.isCommonPassword(null));
        assertFalse(ValidationUtils.isCommonPassword("RandomNonCommonPass1"));
    }

    @Test
    @DisplayName("passwordContainsUsername - password contains username")
    void passwordContainsUsername() {
        assertTrue(ValidationUtils.passwordContainsUsername("john123", "john"));
        assertTrue(ValidationUtils.passwordContainsUsername("MyJohnPass", "john"));
        assertTrue(ValidationUtils.passwordContainsUsername("hello_john!", "JOHN"));
        assertFalse(ValidationUtils.passwordContainsUsername("password123", "john"));
        assertFalse(ValidationUtils.passwordContainsUsername(null, "john"));
        assertFalse(ValidationUtils.passwordContainsUsername("password", null));
        assertFalse(ValidationUtils.passwordContainsUsername(null, null));
    }
}
