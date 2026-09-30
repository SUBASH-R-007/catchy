package io.cachelab.advisor;

import io.cachelab.PolicyType;
import java.util.Objects;

/**
 * A policy recommendation from the {@link PolicyAdvisor}: switching from {@code current} to {@code
 * recommended} would have raised the hit rate by {@code expectedGainPts} percentage points over the
 * last {@code windowSec} seconds of real traffic, as measured by shadow caches.
 *
 * <p>Immutable and thread-safe.
 *
 * @param current the policy the cache uses now
 * @param recommended the better policy
 * @param expectedGainPts the measured advantage in percentage points (e.g. 7.4)
 * @param windowSec the window the advantage held over
 */
public record Recommendation(
    PolicyType current, PolicyType recommended, double expectedGainPts, int windowSec) {

  /**
   * Validates the components.
   *
   * @throws NullPointerException if a policy is null
   */
  public Recommendation {
    Objects.requireNonNull(current, "current");
    Objects.requireNonNull(recommended, "recommended");
  }
}
