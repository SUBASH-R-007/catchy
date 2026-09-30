package io.cachelab.server.metrics;

import io.cachelab.server.workload.Pattern;

/**
 * Status of the running simulation in a {@link MetricsSnapshot} ({@code $defs/simulation} in the
 * schema).
 *
 * <p>Immutable and thread-safe.
 *
 * @param id simulation id; never {@code null}
 * @param running whether the simulation is still running
 * @param group the group the simulation drives; never {@code null}
 * @param pattern the workload pattern of the current phase; never {@code null}
 * @param act guided demo act number (1-4), or {@code null} for a free-running simulation
 * @param phaseIndex zero-based index of the current phase
 * @param phaseCount number of phases (at least 1)
 * @param phaseCaption caption of the current phase, or {@code null} when it has none
 * @param phaseStartedTs epoch milliseconds when the current phase began
 */
public record SimulationStatus(
    String id,
    boolean running,
    String group,
    Pattern pattern,
    Integer act,
    int phaseIndex,
    int phaseCount,
    String phaseCaption,
    long phaseStartedTs) {}
