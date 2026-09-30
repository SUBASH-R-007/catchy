package io.cachelab.diagnostics;

import java.util.List;
import java.util.Objects;

/**
 * The outcome of a stress run (SPEC 5).
 *
 * <p>Immutable and thread-safe.
 *
 * @param impl the engine that was stressed
 * @param threads worker threads
 * @param durationMs measured run time in milliseconds
 * @param totalOps gets plus puts completed by all workers
 * @param opsPerSec throughput over the measured run time
 * @param invariants the five invariant results, in a fixed order
 * @param exceptions up to 10 exception summaries from the workers
 * @param deadlockFree whether no deadlock was detected after the run
 */
public record StressReport(
    StressConfig.Impl impl,
    int threads,
    long durationMs,
    long totalOps,
    double opsPerSec,
    List<InvariantResult> invariants,
    List<String> exceptions,
    boolean deadlockFree) {

  /**
   * Validates and copies the lists.
   *
   * @throws NullPointerException if a component is null
   */
  public StressReport {
    Objects.requireNonNull(impl, "impl");
    invariants = List.copyOf(invariants);
    exceptions = List.copyOf(exceptions);
  }

  /**
   * Returns whether every invariant held and no deadlock was found.
   *
   * @return true when the run is clean
   */
  public boolean passed() {
    return deadlockFree && invariants.stream().allMatch(InvariantResult::passed);
  }

  /**
   * One invariant check.
   *
   * @param name the invariant, e.g. {@code "Size bound"}
   * @param passed whether it held
   * @param detail a plain-language explanation of what was measured
   */
  public record InvariantResult(String name, boolean passed, String detail) {}
}
