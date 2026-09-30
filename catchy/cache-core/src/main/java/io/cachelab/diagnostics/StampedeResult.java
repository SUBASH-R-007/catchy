package io.cachelab.diagnostics;

/**
 * The outcome of a {@link StampedeTest}.
 *
 * <p>Immutable and thread-safe.
 *
 * @param threads concurrent callers
 * @param loaderCalls how many times the loader ran (expected: 1)
 * @param allSameValue whether every caller received the same value
 * @param durationMs time from the start signal until every caller returned
 */
public record StampedeResult(int threads, int loaderCalls, boolean allSameValue, long durationMs) {

  /**
   * Returns whether single-flight loading held.
   *
   * @return true when the loader ran once and everyone got its value
   */
  public boolean passed() {
    return loaderCalls == 1 && allSameValue;
  }
}
