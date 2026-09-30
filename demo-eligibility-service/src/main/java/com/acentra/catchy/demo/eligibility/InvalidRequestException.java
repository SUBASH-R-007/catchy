package com.acentra.catchy.demo.eligibility;

import java.util.regex.Pattern;

/** Thrown for malformed ids. The message never echoes the rejected value. */
public class InvalidRequestException extends RuntimeException {

    private static final Pattern SYNTHETIC_ID = Pattern.compile("^SYN-[0-9]{1,9}$");

    public InvalidRequestException(String message) {
        super(message);
    }

    /** Validates a synthetic member / authorization request id such as {@code SYN-000123}. */
    static String requireSyntheticId(String value, String name) {
        if (value == null || !SYNTHETIC_ID.matcher(value).matches()) {
            throw new InvalidRequestException(name + " must match " + SYNTHETIC_ID.pattern() + " (synthetic ids only)");
        }
        return value;
    }
}
