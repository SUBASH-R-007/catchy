package com.acentra.catchy.demo.eligibility;

import com.acentra.cache.CacheMemoryEstimator;

/**
 * SDK {@link CacheMemoryEstimator} extension point: charges each entry at least a fixed, conservative footprint.
 *
 * <p>The cached eligibility value is deliberately tiny (three short fields, ~350 bytes by the standard estimate). A real
 * production entry costs far more once the object graph, serialized envelope and index bookkeeping are counted, so the
 * demo models that cost instead of pretending the entries are free. This is what makes the 512 KB memory limit (and thus
 * memory-limit evictions) meaningful for this region. The figure remains an <b>estimate</b>, not exact JVM accounting.
 */
final class FixedFootprintEstimator implements CacheMemoryEstimator {
    private final long minimumFootprintBytes;
    private final CacheMemoryEstimator standard = CacheMemoryEstimator.standard();

    FixedFootprintEstimator(long minimumFootprintBytes) {
        if (minimumFootprintBytes < 1) throw new IllegalArgumentException("minimumFootprintBytes must be >= 1");
        this.minimumFootprintBytes = minimumFootprintBytes;
    }

    @Override
    public long estimateBytes(Object key, Object value) {
        return Math.max(minimumFootprintBytes, standard.estimateBytes(key, value));
    }
}
