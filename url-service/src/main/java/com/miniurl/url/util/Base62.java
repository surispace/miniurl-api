package com.miniurl.url.util;

import java.util.Objects;

/**
 * Utility class for Base62 encoding and decoding.
 * Used to convert Snowflake IDs into short, URL-friendly strings.
 */
public class Base62 {

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    public static String encode(long value) {
        if (value == 0) {
            return String.valueOf(ALPHABET.charAt(0));
        }

        StringBuilder sb = new StringBuilder();
        while (value > 0) {
            sb.append(ALPHABET.charAt((int) (value % 62)));
            value /= 62;
        }
        return sb.reverse().toString();
    }

    public static long decode(String value) {
        Objects.requireNonNull(value, "Value to decode cannot be null");
        long result = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            int digit = ALPHABET.indexOf(c);
            if (digit == -1) {
                throw new IllegalArgumentException("Invalid character in Base62 string: " + c);
            }
            result = result * 62 + digit;
        }
        return result;
    }
}
