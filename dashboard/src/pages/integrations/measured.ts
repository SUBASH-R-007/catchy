/**
 * Latency measured by examples/formulary-service's LatencyComparisonTest (SPEC 7): the average
 * time of 2,000 Zipf-distributed lookups without and with the cache. Fill this in with the numbers
 * the test prints; never invent them. While it is null the page says how to get them.
 */
export interface MeasuredLatency {
  /** Average lookup time without the cache, in milliseconds. */
  uncachedAvgMs: number;
  /** Average lookup time with the cache, in milliseconds. */
  cachedAvgMs: number;
  /** Number of lookups per run (2,000 in the test). */
  lookups: number;
  /** Where and when the numbers were measured, e.g. "LatencyComparisonTest, 2026-09-30, JDK 21". */
  source: string;
}

export const MEASURED_LATENCY: MeasuredLatency | null = null;
