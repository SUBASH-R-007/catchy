package io.cachelab.server.workload;

import java.util.SplittableRandom;

/**
 * Produces the key of each operation of a workload (SPEC 9.1).
 *
 * <p>A stream holds no randomness of its own: every random choice is drawn from {@code rnd}, and
 * time-dependent patterns read only {@code elapsedMs}, which the simulation runner derives from the
 * operation index (<em>logical time</em>, see {@link KeyStreams}). The same seed and the same
 * sequence of calls therefore always give the same keys.
 *
 * <p>Implementations are not thread-safe; each simulation uses its own instance on its single
 * generator thread.
 */
@FunctionalInterface
public interface KeyStream {

  /**
   * Returns the key of the next operation.
   *
   * @param rnd the simulation's random stream; never {@code null}
   * @param opIndex zero-based index of this operation within the current phase
   * @param elapsedMs logical time since the phase began, in milliseconds
   * @return the key; never {@code null}
   */
  String next(SplittableRandom rnd, long opIndex, long elapsedMs);
}
