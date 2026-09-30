package com.acentra.cache;

import com.acentra.cache.telemetry.TelemetryJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

/**
 * byte[] length, String length, boxed primitives, otherwise serialized JSON length (if serializable),
 * otherwise a configurable fallback object size. Every entry also pays a fixed bookkeeping overhead.
 */
public final class StandardCacheMemoryEstimator implements CacheMemoryEstimator {
    static final long ENTRY_OVERHEAD_BYTES = 128;
    private static final ObjectMapper MAPPER = TelemetryJson.mapper();

    private final long fallbackObjectBytes;

    public StandardCacheMemoryEstimator(long fallbackObjectBytes) {
        if (fallbackObjectBytes < 1) throw new IllegalArgumentException("fallbackObjectBytes must be >= 1");
        this.fallbackObjectBytes = fallbackObjectBytes;
    }

    @Override
    public long estimateBytes(Object key, Object value) {
        return ENTRY_OVERHEAD_BYTES + sizeOf(key) + sizeOf(value);
    }

    private long sizeOf(Object o) {
        if (o == null) return 0;
        if (o instanceof byte[] b) return 16L + b.length;
        if (o instanceof CharSequence s) return 40L + s.toString().getBytes(StandardCharsets.UTF_8).length;
        if (o instanceof Boolean || o instanceof Byte) return 16;
        if (o instanceof Integer || o instanceof Short || o instanceof Character || o instanceof Float) return 16;
        if (o instanceof Long || o instanceof Double) return 24;
        if (o instanceof Enum<?>) return 16;
        if (o instanceof BigDecimal || o instanceof BigInteger) return 48L + o.toString().length();
        try {
            return 48L + MAPPER.writeValueAsBytes(o).length;
        } catch (Exception e) {
            return fallbackObjectBytes;
        }
    }
}
