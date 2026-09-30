package com.acentra.cache;

/** Thrown by {@code getOrLoad} when the source loader fails or returns null. */
public class CacheLoadException extends RuntimeException {
    public CacheLoadException(String message) {
        super(message);
    }

    public CacheLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
