package io.cachelab.advisor;

import io.cachelab.AccessObserver;
import io.cachelab.PolicyType;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Recommends the eviction policy that would have served the live traffic best (SPEC 6.3, ADR-005).
 *
 * <p>Registered as an {@link AccessObserver} on a real cache, it feeds every lookup key to one
 * {@link ShadowCache} per {@link PolicyType} — <em>including a shadow of the current policy</em>,
 * so the comparison is shadow-to-shadow (identical conditions, no TTL, no warm-up bias). {@link
 * #evaluate()} (called every second by the server) compares the shadows' hit rates over the last
 * {@value #WINDOW_SEC} s; if the best candidate beats the current policy's shadow by more than
 * {@value #THRESHOLD_PTS} percentage points, it publishes a {@link Recommendation}.
 *
 * <p>The advisor never switches the cache itself: a person applies the recommendation. After a
 * switch, call {@link #reset()} so the next recommendation is based on fresh evidence.
 *
 * <p>Thread-safety: safe for concurrent use. Memory: three keys-only shadows of the cache's
 * capacity.
 *
 * @param <K> the key type
 */
public final class PolicyAdvisor<K> implements AccessObserver<K> {

  /** Evaluation window in seconds. */
  public static final int WINDOW_SEC = 30;

  /** Minimum advantage, in percentage points, before recommending a switch. */
  public static final double THRESHOLD_PTS = 3.0;

  /** Minimum lookups in the window before any recommendation. */
  public static final long MIN_REQUESTS = 1_000;

  private final Map<PolicyType, ShadowCache<K>> shadows = new EnumMap<>(PolicyType.class);
  private final Supplier<PolicyType> currentPolicy;
  private final LongSupplier clockMillis;
  private volatile long evidenceSinceMs;
  private volatile Recommendation latest;

  /**
   * Creates an advisor.
   *
   * @param capacity the real cache's maximum size
   * @param currentPolicy reads the real cache's current policy
   * @param clockMillis time source in milliseconds (wall clock in production, logical in tests)
   * @param decayIntervalMs decay interval for the LFU_DECAY shadow
   */
  public PolicyAdvisor(
      int capacity,
      Supplier<PolicyType> currentPolicy,
      LongSupplier clockMillis,
      long decayIntervalMs) {
    this.currentPolicy = Objects.requireNonNull(currentPolicy, "currentPolicy");
    this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
    for (PolicyType type : PolicyType.values()) {
      shadows.put(type, new ShadowCache<>(capacity, type, clockMillis, decayIntervalMs));
    }
    this.evidenceSinceMs = clockMillis.getAsLong();
  }

  @Override
  public void onAccess(K key, boolean hit) {
    for (ShadowCache<K> shadow : shadows.values()) {
      shadow.access(key);
    }
  }

  /**
   * Compares the shadows over the last {@value #WINDOW_SEC} s and updates the recommendation. A
   * recommendation needs a full window of evidence since the last reset, at least {@value
   * #MIN_REQUESTS} lookups, and an advantage above {@value #THRESHOLD_PTS} points.
   *
   * @return the current recommendation, or empty
   */
  public Optional<Recommendation> evaluate() {
    PolicyType current = currentPolicy.get();
    boolean fullWindow = clockMillis.getAsLong() - evidenceSinceMs >= WINDOW_SEC * 1000L;
    ShadowCache<K> baseline = shadows.get(current);
    if (!fullWindow || baseline.windowRequests(WINDOW_SEC) < MIN_REQUESTS) {
      latest = null;
      return Optional.empty();
    }
    double baselineRate = baseline.windowHitRate(WINDOW_SEC);
    PolicyType best = current;
    double bestRate = baselineRate;
    for (Map.Entry<PolicyType, ShadowCache<K>> e : shadows.entrySet()) {
      double rate = e.getValue().windowHitRate(WINDOW_SEC);
      if (rate > bestRate) {
        best = e.getKey();
        bestRate = rate;
      }
    }
    double gainPts = (bestRate - baselineRate) * 100;
    latest =
        best != current && gainPts > THRESHOLD_PTS
            ? new Recommendation(current, best, Math.round(gainPts * 10) / 10.0, WINDOW_SEC)
            : null;
    return Optional.ofNullable(latest);
  }

  /**
   * Returns the recommendation from the last {@link #evaluate()}.
   *
   * @return the recommendation, or empty
   */
  public Optional<Recommendation> recommendation() {
    return Optional.ofNullable(latest);
  }

  /**
   * Returns each shadow's hit rate over the last {@value #WINDOW_SEC} s.
   *
   * @return policy to windowed hit rate
   */
  public Map<PolicyType, Double> windowHitRates() {
    Map<PolicyType, Double> rates = new EnumMap<>(PolicyType.class);
    shadows.forEach((type, shadow) -> rates.put(type, shadow.windowHitRate(WINDOW_SEC)));
    return rates;
  }

  /** Clears the windows and the recommendation; call after the cache's policy changed. */
  public void reset() {
    shadows.values().forEach(ShadowCache::resetWindow);
    evidenceSinceMs = clockMillis.getAsLong();
    latest = null;
  }
}
