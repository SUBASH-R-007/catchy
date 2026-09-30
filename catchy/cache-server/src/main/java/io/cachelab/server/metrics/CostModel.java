package io.cachelab.server.metrics;

import java.util.Objects;

/**
 * Turns hit counts into the dashboard's cost estimates (SPEC 9.3). Every hit is a database call
 * avoided.
 *
 * <p>Immutable and thread-safe; all methods are O(1).
 *
 * @param meanDbLatencyMs mean simulated database latency per call, in milliseconds (at least 0)
 * @param costPerThousandCalls estimated cost of 1,000 database calls, in {@code currency} (at least
 *     0)
 * @param currency currency label, for example {@code "$"}; never {@code null}
 */
public record CostModel(double meanDbLatencyMs, double costPerThousandCalls, String currency) {

  /**
   * Validates the assumptions.
   *
   * @throws IllegalArgumentException if a number is negative or not finite
   * @throws NullPointerException if {@code currency} is {@code null}
   */
  public CostModel {
    requireNonNegative(meanDbLatencyMs, "meanDbLatencyMs");
    requireNonNegative(costPerThousandCalls, "costPerThousandCalls");
    Objects.requireNonNull(currency, "currency");
  }

  /**
   * Database calls avoided.
   *
   * @param hits cumulative hits
   * @return {@code hits}
   */
  public long dbCallsAvoided(long hits) {
    return hits;
  }

  /**
   * Latency saved by serving hits from the cache.
   *
   * @param hits cumulative hits
   * @return {@code hits x meanDbLatencyMs}, in milliseconds
   */
  public double latencySavedMs(long hits) {
    return hits * meanDbLatencyMs;
  }

  /**
   * Cost saved by serving hits from the cache.
   *
   * @param hits cumulative hits
   * @return {@code hits / 1000 x costPerThousandCalls}, in {@code currency}
   */
  public double estCostSaved(long hits) {
    return hits / 1000.0 * costPerThousandCalls;
  }

  private static void requireNonNegative(double value, String name) {
    if (!(value >= 0) || Double.isInfinite(value)) {
      throw new IllegalArgumentException(name + " must be finite and >= 0: " + value);
    }
  }
}
