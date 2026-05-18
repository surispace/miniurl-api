package com.miniurl.util;

import java.util.Set;

/**
 * Shared validation utilities for user input.
 */
public final class ValidationUtils {

    private ValidationUtils() {}

    /**
     * Username pattern: starts with letter, then alphanumeric or underscore.
     */
    private static final java.util.regex.Pattern USERNAME_PATTERN =
        java.util.regex.Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*$");

    /**
     * Common password list — top worst passwords users pick.
     * Based on published breach data (RockYou, LinkedIn, etc.)
     */
    private static final Set<String> COMMON_PASSWORDS = Set.of(
        "password", "password1", "password123", "123456", "12345678", "123456789", "1234567890",
        "qwerty", "abc123", "monkey", "master", "dragon", "111111", "baseball", "iloveyou",
        "trustno1", "sunshine", "princess", "football", "shadow", "superman", "michael",
        "letmein", "welcome", "hello", "charlie", "donald", "admin", "admin123", "root",
        "toor", "pass", "test", "guest", "master123", "access", "love", "hockey",
        "ranger", "thomas", "george", "summer", "winter", "spring", "autumn"
    );

    private static final Set<String> COMMON_PASSWORDS_EXTRA = Set.of(
        "batman", "killer", "pepper", "ginger", "bubbles", "soccer", "starwars",
        "cheese", "matrix", "harley", "hunter", "falcon", "tigger", "jordan",
        "robert", "daniel", "jessica", "jennifer", "ashley", "samantha", "nicole",
        "buster", "bailey", "andrew", "joshua", "james", "charles", "maggie",
        "secret", "access14", "flower", "ranger1", "soccer1", "hottie", "loveme",
        "1234", "12345", "1234567", "123123", "12345678910", "1q2w3e", "qwerty123",
        "000000", "aaaaaa", "passw0rd", "p@ssword", "p@ssw0rd", "changeme", "123qwe"
    );

    /**
     * Reserved usernames that cannot be registered by regular users.
     */
    private static final Set<String> RESERVED_USERNAMES = Set.of(
        "admin", "administrator", "root", "system", "sysadmin", "sysop",
        "support", "info", "help", "helpdesk", "contact", "webmaster",
        "postmaster", "hostmaster", "noreply", "no-reply", "donotreply",
        "mail", "email", "www", "ftp", "smtp", "api", "app", "apps",
        "www2", "www3", "blog", "status", "server", "security"
    );

    private static final Set<String> RESERVED_USERNAMES_EXTRA = Set.of(
        "abuse", "spam", "report", "feedback", "sales", "billing",
        "legal", "privacy", "terms", "tos", "dmarc", "dev", "developer",
        "developers", "staging", "test", "testing", "demo", "null", "undefined",
        "login", "logout", "signin", "signup", "register", "registration",
        "search", "dashboard", "profile", "settings", "account", "manage"
    );

    /**
     * Check if a username is valid: starts with letter, alphanumeric + underscore, 3-50 chars.
     */
    public static boolean isValidUsername(String username) {
        if (username == null) return false;
        if (username.length() < 3 || username.length() > 50) return false;
        return USERNAME_PATTERN.matcher(username).matches();
    }

    /**
     * Check if a username is reserved.
     */
    public static boolean isReservedUsername(String username) {
        return username != null && (RESERVED_USERNAMES.contains(username.toLowerCase())
                || RESERVED_USERNAMES_EXTRA.contains(username.toLowerCase()));
    }

    /**
     * Check if a password is in the common password list.
     */
    public static boolean isCommonPassword(String password) {
        return password != null && (COMMON_PASSWORDS.contains(password.toLowerCase())
                || COMMON_PASSWORDS_EXTRA.contains(password.toLowerCase()));
    }

    /**
     * Check if password contains the username (case-insensitive).
     */
    public static boolean passwordContainsUsername(String password, String username) {
        if (password == null || username == null) return false;
        return password.toLowerCase().contains(username.toLowerCase());
    }
}
