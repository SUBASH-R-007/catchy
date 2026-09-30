package io.cachelab.server.simulation;

import io.cachelab.server.workload.Pattern;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What a simulation runs: a group, a rate, a read ratio, a seed and one or more phases executed in
 * order (SPEC 9.2). A free-running simulation ({@code POST /api/simulations}) is a one-phase plan;
 * a guided demo act (SPEC 9.4) is a multi-phase plan with {@code act} set.
 *
 * <p>Immutable and thread-safe.
 *
 * @param group the group whose caches receive the workload; never {@code null}
 * @param opsPerSec target operations per second, at least 0 (0 = unthrottled)
 * @param readRatio probability that an operation is a read, in [0, 1]
 * @param seed seed of the workload's single random stream
 * @param act guided demo act number, or {@code null} for a free-running simulation
 * @param phases the phases, at least one; never {@code null}
 */
public record SimulationPlan(
    String group, int opsPerSec, double readRatio, long seed, Integer act, List<Phase> phases) {

  /**
   * Validates and copies.
   *
   * @throws IllegalArgumentException if a number is out of range or there is no phase
   * @throws NullPointerException if {@code group}, {@code phases} or a phase is {@code null}
   */
  public SimulationPlan {
    Objects.requireNonNull(group, "group");
    phases = List.copyOf(Objects.requireNonNull(phases, "phases"));
    if (phases.isEmpty()) {
      throw new IllegalArgumentException("a plan needs at least one phase");
    }
    if (opsPerSec < 0) {
      throw new IllegalArgumentException("opsPerSec must be >= 0: " + opsPerSec);
    }
    if (!(readRatio >= 0 && readRatio <= 1)) {
      throw new IllegalArgumentException("readRatio must be in [0, 1]: " + readRatio);
    }
  }

  /**
   * A one-phase plan whose caption describes the pattern.
   *
   * @param group the group
   * @param pattern the pattern
   * @param opsPerSec operations per second (0 = unthrottled)
   * @param readRatio read probability
   * @param durationSec phase length in seconds
   * @param seed random seed
   * @param params pattern parameters, or {@code null}
   * @param caption the phase caption, or {@code null}
   * @return the plan
   */
  public static SimulationPlan single(
      String group,
      Pattern pattern,
      int opsPerSec,
      double readRatio,
      int durationSec,
      long seed,
      Map<String, Object> params,
      String caption) {
    return new SimulationPlan(
        group,
        opsPerSec,
        readRatio,
        seed,
        null,
        List.of(new Phase(pattern, durationSec, caption, params)));
  }

  /**
   * One phase of a plan.
   *
   * @param pattern the workload pattern; never {@code null}
   * @param durationSec wall-clock length of the phase, at least 1 second
   * @param caption plain-language caption shown in the stream, or {@code null}
   * @param params pattern parameter overrides; {@code null} means none; copied, read-only
   */
  public record Phase(
      Pattern pattern, int durationSec, String caption, Map<String, Object> params) {

    /**
     * Validates and copies.
     *
     * @throws IllegalArgumentException if {@code durationSec < 1}
     * @throws NullPointerException if {@code pattern} is {@code null}
     */
    public Phase {
      Objects.requireNonNull(pattern, "pattern");
      if (durationSec < 1) {
        throw new IllegalArgumentException("durationSec must be >= 1: " + durationSec);
      }
      params = params == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }
  }
}
