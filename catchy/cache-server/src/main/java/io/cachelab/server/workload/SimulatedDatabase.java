package io.cachelab.server.workload;

import java.util.Objects;
import java.util.SplittableRandom;

/**
 * The slow backing store behind a simulated cache (SPEC 9.3). It never sleeps: each {@link #load}
 * <em>accounts</em> a latency drawn from a seeded uniform distribution of {@value #MIN_LATENCY_MS}
 * to {@value #MAX_LATENCY_MS} ms and counts the call, so simulations run at full speed while still
 * reporting what the misses would have cost.
 *
 * <p>Its random stream is separate from the workload's, so loading never changes the key sequence.
 *
 * <p>Not thread-safe: owned by one simulation thread; read the counters after the run ends. {@link
 * #load} is O(1).
 */
public final class SimulatedDatabase {

  /** Lower bound of the simulated latency, in milliseconds. */
  public static final double MIN_LATENCY_MS = 5.0;

  /** Upper bound of the simulated latency, in milliseconds. */
  public static final double MAX_LATENCY_MS = 20.0;

  private final SplittableRandom rnd;
  private long calls;
  private double totalLatencyMs;

  /**
   * Creates a database.
   *
   * @param seed seed of the latency distribution
   */
  public SimulatedDatabase(long seed) {
    this.rnd = new SplittableRandom(seed);
  }

  /**
   * Loads a value, accounting one call and its simulated latency.
   *
   * @param key the key; never {@code null}
   * @return {@code "db:" + key}
   */
  public String load(String key) {
    Objects.requireNonNull(key, "key");
    calls++;
    totalLatencyMs += rnd.nextDouble(MIN_LATENCY_MS, MAX_LATENCY_MS);
    return "db:" + key;
  }

  /**
   * Returns the number of {@link #load} calls.
   *
   * @return the call count
   */
  public long calls() {
    return calls;
  }

  /**
   * Returns the total simulated latency of all calls.
   *
   * @return milliseconds
   */
  public double totalLatencyMs() {
    return totalLatencyMs;
  }

  /**
   * Returns the mean simulated latency per call.
   *
   * @return milliseconds, or 0 when there were no calls
   */
  public double meanLatencyMs() {
    return calls == 0 ? 0.0 : totalLatencyMs / calls;
  }
}
