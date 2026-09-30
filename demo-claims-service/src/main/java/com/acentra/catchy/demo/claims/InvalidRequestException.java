package com.acentra.catchy.demo.claims;

import java.util.regex.Pattern;

/** Thrown for malformed ids. The message never echoes the rejected value. */
public class InvalidRequestException extends RuntimeException {

    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9-]{1,40}$");

    public InvalidRequestException(String message) {
        super(message);
    }

    /** Validates {@code ruleSetId} / {@code providerGroupId}: 1-40 letters, digits or hyphens. */
    static String requireId(String value, String name) {
        if (value == null || !ID.matcher(value).matches()) {
            throw new InvalidRequestException(name + " must match " + ID.pattern());
        }
        return value;
    }
}
