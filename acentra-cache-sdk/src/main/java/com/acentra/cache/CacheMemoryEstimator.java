package com.acentra.cache;

/**
 * Estimates the memory an entry occupies. This is an <b>estimate</b> (serialized size + fixed overhead),
 * not exact JVM heap accounting.
 */
@FunctionalInterface
public interface CacheMemoryEstimator {
    long estimateBytes(Object key, Object value);

    static CacheMemoryEstimator standard() {
        return new StandardCacheMemoryEstimator(256);
    }

    static CacheMemoryEstimator standard(long fallbackObjectBytes) {
        return new StandardCacheMemoryEstimator(fallbackObjectBytes);
    }
}
