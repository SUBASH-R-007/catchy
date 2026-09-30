package io.cachelab.server.metrics;

import io.cachelab.RemovalCause;

/**
 * One removal from a cache, reported in the tick that follows it ({@code $defs/event} in the
 * schema).
 *
 * <p>Immutable and thread-safe. No component is {@code null}.
 *
 * @param ts epoch milliseconds of the removal
 * @param cache name of the cache the entry left
 * @param key the removed key, as a string
 * @param cause why the entry left
 */
public record RemovalEvent(long ts, String cache, String key, RemovalCause cause) {}
