package io.cachelab.server.metrics;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.cachelab.PolicyType;

/**
 * Metrics of one cache in a {@link MetricsSnapshot} ({@code $defs/cache} in the schema). Counters
 * are cumulative and monotonic except after a stats reset; rates are in [0, 1].
 *
 * <p>Immutable and thread-safe. No component is {@code null}.
 *
 * @param name cache name
 * @param group name of the comparison group the cache belongs to
 * @param policy current eviction policy
 * @param size live entries
 * @param capacity maximum entries
 * @param hits cumulative hits
 * @param misses cumulative misses
 * @param hitRate cumulative {@code hits / (hits + misses)}, 0 when there were no requests
 * @param hitRateWindow10s hit rate over the last 20 ticks (10 s)
 * @param evictions cumulative evictions
 * @param expirations cumulative expirations
 * @param opsPerSec requests per second during the last tick
 * @param getP50Micros median {@code get} latency, microseconds
 * @param getP99Micros 99th percentile {@code get} latency, microseconds
 * @param dbCallsAvoided estimate: equals {@code hits}
 * @param latencySavedMs estimate: {@code hits} times the mean simulated database latency
 * @param estCostSaved estimate: {@code dbCallsAvoided / 1000} times the cost per 1,000 calls
 */
public record CacheMetrics(
    String name,
    String group,
    PolicyType policy,
    long size,
    long capacity,
    long hits,
    long misses,
    double hitRate,
    double hitRateWindow10s,
    long evictions,
    long expirations,
    double opsPerSec,
    // Pinned names: a component starting with "get" must not be read as a bean getter.
    @JsonProperty("getP50Micros") double getP50Micros,
    @JsonProperty("getP99Micros") double getP99Micros,
    long dbCallsAvoided,
    double latencySavedMs,
    double estCostSaved) {}
