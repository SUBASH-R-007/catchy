package io.cachelab.server.workload;

/**
 * The workload patterns of the workload lab (SPEC 9.1). The constant names are part of the metrics
 * stream contract ({@code simulation.pattern} in {@code docs/api/metrics.schema.json}) and
 * serialize as their names.
 *
 * <p>Thread-safety: enum constants are immutable and safe to share.
 */
public enum Pattern {
  /** Keys drawn uniformly from the key space. */
  UNIFORM,
  /** Zipf-distributed keys (s = 1.0). */
  ZIPF,
  /** Zipf traffic interleaved with a one-off sweep of cold keys. */
  SCAN_POLLUTION,
  /** A cyclic loop slightly larger than the cache. */
  LOOP,
  /** Zipf traffic whose hot set moves periodically. */
  SHIFTING_HOTSPOT,
  /** Zipf traffic where a share of puts carries a short TTL. */
  TTL_BURST,
  /** Realistic drug-formulary lookups. */
  FORMULARY,
  /** Realistic provider-directory lookups with a moving regional hotspot. */
  PROVIDER_DIRECTORY
}
