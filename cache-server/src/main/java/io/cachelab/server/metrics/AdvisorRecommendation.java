package io.cachelab.server.metrics;

import io.cachelab.PolicyType;

/**
 * A policy advisor's recommendation for a group ({@code $defs/advisor} in the schema).
 *
 * <p>Immutable and thread-safe. No component is {@code null}.
 *
 * @param current the policy the group's first cache uses now
 * @param recommended the policy the advisor recommends
 * @param expectedGainPts expected hit-rate gain, in percentage points (at least 0)
 * @param windowSec length of the observation window, in seconds (at least 1)
 */
public record AdvisorRecommendation(
    PolicyType current, PolicyType recommended, double expectedGainPts, int windowSec) {}
