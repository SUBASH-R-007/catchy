package io.cachelab.server.cache;

import io.cachelab.PolicyType;
import io.cachelab.advisor.KeyRecorder;
import io.cachelab.advisor.OptimalReplay;
import io.cachelab.advisor.PolicyAdvisor;
import io.cachelab.advisor.Recommendation;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;

/**
 * The intelligence of one comparison group (SPEC 6.3, 6.4, 8.1): a {@link KeyRecorder} and a
 * {@link PolicyAdvisor} attached to the group's <em>primary</em> cache — the earliest-created
 * member that still exists — plus the latest optimal (Bélády) hit rate over the recorded trace.
 *
 * <p>Every member cache forwards its lookups through {@link #onAccess}, but only the primary's
 * lookups are recorded, so each key of a group-wide workload is counted once. When the primary
 * changes (it was deleted, or the group was recreated) the recorder, the advisor and the optimal
 * hit rate start again from scratch, sized to the new primary's capacity.
 *
 * <p>Thread-safe: membership changes are synchronized; the access path reads volatile fields only.
 */
public final class CacheGroup {

  /** Decay interval of the advisor's LFU_DECAY shadow, matching the engine default. */
  public static final long ADVISOR_DECAY_INTERVAL_MS = 10_000;

  private final String name;
  private final LongSupplier clockMillis;
  private final List<ManagedCache> members = new CopyOnWriteArrayList<>();
  private final KeyRecorder<String> recorder = new KeyRecorder<>();
  private volatile Intel intel;

  /**
   * Creates an empty group.
   *
   * @param name the group name; never {@code null}
   * @param clockMillis wall clock for the advisor's windows, in milliseconds
   */
  public CacheGroup(String name, LongSupplier clockMillis) {
    this.name = Objects.requireNonNull(name, "name");
    this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
  }

  /**
   * Returns the group name.
   *
   * @return the name
   */
  public String name() {
    return name;
  }

  /**
   * Returns the members in creation order.
   *
   * @return a snapshot of the members; empty once every member was deleted
   */
  public List<ManagedCache> members() {
    return List.copyOf(members);
  }

  /**
   * Returns the primary cache: the earliest-created member that still exists.
   *
   * @return the primary, or {@code null} when the group is empty
   */
  public ManagedCache primary() {
    Intel current = intel;
    return current == null ? null : current.primary;
  }

  synchronized void add(ManagedCache cache) {
    members.add(Objects.requireNonNull(cache, "cache"));
    if (members.size() == 1) {
      restart();
    }
  }

  synchronized void remove(ManagedCache cache) {
    boolean wasPrimary = primary() == cache;
    members.remove(cache);
    if (wasPrimary) {
      restart();
    }
  }

  synchronized boolean isEmpty() {
    return members.isEmpty();
  }

  /** Forgets all evidence and attaches a fresh advisor to the current first member. */
  private void restart() {
    recorder.clear();
    if (members.isEmpty()) {
      intel = null;
      return;
    }
    ManagedCache primary = members.get(0);
    PolicyAdvisor<String> advisor =
        new PolicyAdvisor<>(
            primary.config().capacity(),
            () -> primary.config().policy(),
            clockMillis,
            ADVISOR_DECAY_INTERVAL_MS);
    intel = new Intel(primary, advisor);
  }

  /**
   * Receives one lookup from a member cache; records it only if {@code source} is the primary.
   *
   * @param source the cache that served the lookup
   * @param key the key
   * @param hit whether it hit
   */
  void onAccess(ManagedCache source, String key, boolean hit) {
    Intel current = intel;
    if (current != null && current.primary == source) {
      recorder.onAccess(key, hit);
      current.advisor.onAccess(key, hit);
    }
  }

  /**
   * Re-evaluates the advisor over its window (called every second by the scheduler).
   *
   * @return the recommendation, or empty
   */
  public Optional<Recommendation> evaluate() {
    Intel current = intel;
    return current == null ? Optional.empty() : current.advisor.evaluate();
  }

  /**
   * Returns the recommendation from the last evaluation.
   *
   * @return the recommendation, or empty
   */
  public Optional<Recommendation> recommendation() {
    Intel current = intel;
    return current == null ? Optional.empty() : current.advisor.recommendation();
  }

  /** Clears the advisor's windows and recommendation; call after the primary's policy changed. */
  public void resetAdvisor() {
    Intel current = intel;
    if (current != null) {
      current.advisor.reset();
    }
  }

  /**
   * Recomputes the optimal hit rate over the recorded trace: O(N log C) for N recorded keys, so it
   * runs on the background scheduler, never on a request or metrics thread.
   */
  public void computeOptimal() {
    Intel current = intel;
    if (current == null) {
      return;
    }
    List<String> trace = recorder.snapshot();
    Double rate =
        trace.isEmpty()
            ? null
            : OptimalReplay.hitRate(trace, current.primary.config().capacity());
    if (intel == current) { // skip if the primary changed meanwhile: the trace was for the old one
      current.optimalHitRate = rate;
    }
  }

  /**
   * Returns the latest optimal hit rate.
   *
   * @return Bélády's hit rate over the recorded trace, or {@code null} before the first
   *     computation or while nothing was recorded
   */
  public Double optimalHitRate() {
    Intel current = intel;
    return current == null ? null : current.optimalHitRate;
  }

  /**
   * Returns the primary's current policy.
   *
   * @return the policy, or {@code null} when the group is empty
   */
  public PolicyType primaryPolicy() {
    ManagedCache primary = primary();
    return primary == null ? null : primary.config().policy();
  }

  /**
   * Returns how many lookups the recorder saw since the primary last changed.
   *
   * @return the lookup count
   */
  public long recordedLookups() {
    return recorder.totalRecorded();
  }

  /** The advisor state tied to one primary; replaced as a whole when the primary changes. */
  private static final class Intel {
    final ManagedCache primary;
    final PolicyAdvisor<String> advisor;
    volatile Double optimalHitRate;

    Intel(ManagedCache primary, PolicyAdvisor<String> advisor) {
      this.primary = primary;
      this.advisor = advisor;
    }
  }
}
