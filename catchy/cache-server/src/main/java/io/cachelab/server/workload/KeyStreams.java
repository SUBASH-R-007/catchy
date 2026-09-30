package io.cachelab.server.workload;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Builds the {@link KeyStream} of each {@link Pattern} (SPEC 9.1).
 *
 * <p><b>Logical time.</b> Time-based patterns ({@code SHIFTING_HOTSPOT}, {@code FORMULARY}, {@code
 * PROVIDER_DIRECTORY}) never read a clock. The runner passes {@code elapsedMs = opIndex x 1000 /
 * effectiveRate}, where {@code effectiveRate} is the simulation's {@code opsPerSec}, or {@value
 * #UNTHROTTLED_RATE} when it is unthrottled ({@code opsPerSec = 0}). The key sequence is therefore
 * a pure function of the seed, the parameters and the operation index.
 *
 * <p><b>Defaults and parameters</b> ({@code params} overrides, all optional):
 *
 * <ul>
 *   <li>{@code UNIFORM}: {@code key:<n>}, n uniform in {@code [0, keySpace)}; keySpace 10,000.
 *   <li>{@code ZIPF}, {@code TTL_BURST}: {@code key:<rank>}, Zipf over keySpace 10,000 with zipfS
 *       1.0. The TTL rule of {@code TTL_BURST} is applied by the runner.
 *   <li>{@code SCAN_POLLUTION}: odd operations are Zipf hot traffic as above; even operations are
 *       cold keys {@code cold:<n>} sweeping n = 0..coldKeys-1 cyclically (coldKeys 5,000), i.e.
 *       interleaved 1:1 for the whole run.
 *   <li>{@code LOOP}: {@code key:<opIndex mod loopSize>}; loopSize 1,100. With probability
 *       rereadShare (0.02) an operation instead re-reads a uniformly random loop key. Without it a
 *       cold LFU degenerates to LRU on a strict loop (every entry stays at frequency 1 and the LRU
 *       tie-break evicts the next key needed); the occasional re-read lets LFU accumulate
 *       frequencies and keep most of the loop, while LRU stays near 0%. Set it to 0 for a strict
 *       loop.
 *   <li>{@code SHIFTING_HOTSPOT}: Zipf as above, key = {@code (rank + offset) mod keySpace} where
 *       the offset grows by shiftBy (2,000) every shiftEverySec (20) seconds of logical time.
 *   <li>{@code FORMULARY}: {@code drug:<rank>} over 50,000 drugs, Zipf zipfS 1.1. During the last
 *       10 s of every 40 s ("morning surge") half of the requests go uniformly to 200 fixed common
 *       drugs spread over the catalogue.
 *   <li>{@code PROVIDER_DIRECTORY}: {@code prov:r<region>:<n>}, 10 regions x 2,000 providers. 70%
 *       of requests go to the hot region, the rest to a uniformly chosen region; within a region n
 *       is Zipf (zipfS 1.0). The hot region moves to the next one every 30 s of logical time.
 * </ul>
 *
 * <p>Unknown parameter names and out-of-range values are rejected. Thread-safe (stateless).
 */
public final class KeyStreams {

  /** Logical operations per second assumed for an unthrottled simulation. */
  public static final int UNTHROTTLED_RATE = 5_000;

  static final int DEFAULT_KEY_SPACE = 10_000;
  static final double DEFAULT_ZIPF_S = 1.0;
  static final int DEFAULT_COLD_KEYS = 5_000;
  static final int DEFAULT_LOOP_SIZE = 1_100;
  static final double DEFAULT_REREAD_SHARE = 0.02;
  static final int DEFAULT_SHIFT_BY = 2_000;
  static final int DEFAULT_SHIFT_EVERY_SEC = 20;
  static final int DRUGS = 50_000;
  static final double FORMULARY_ZIPF_S = 1.1;
  static final int COMMON_DRUGS = 200;
  static final long SURGE_CYCLE_MS = 40_000;
  static final long SURGE_START_MS = 30_000;
  static final double SURGE_SHARE = 0.5;
  static final int REGIONS = 10;
  static final int PROVIDERS_PER_REGION = 2_000;
  static final double HOT_REGION_SHARE = 0.7;
  static final long REGION_MOVE_MS = 30_000;

  private static final int MAX_KEY_SPACE = 1_000_000;

  private KeyStreams() {}

  /**
   * Creates the stream of a pattern.
   *
   * @param pattern the pattern; never {@code null}
   * @param params parameter overrides; {@code null} or empty for the defaults
   * @return a new stream
   * @throws IllegalArgumentException if a parameter is unknown for the pattern or out of range
   */
  public static KeyStream create(Pattern pattern, Map<String, Object> params) {
    Objects.requireNonNull(pattern, "pattern");
    Params p = new Params(pattern, params == null ? Map.of() : params);
    return switch (pattern) {
      case UNIFORM -> uniform(p);
      case ZIPF, TTL_BURST -> zipf(p);
      case SCAN_POLLUTION -> scanPollution(p);
      case LOOP -> loop(p);
      case SHIFTING_HOTSPOT -> shiftingHotspot(p);
      case FORMULARY -> formulary(p);
      case PROVIDER_DIRECTORY -> providerDirectory(p);
    };
  }

  /**
   * A short plain-language description of a pattern, used as the caption of a one-phase run.
   *
   * @param pattern the pattern; never {@code null}
   * @return the caption
   */
  public static String describe(Pattern pattern) {
    return switch (pattern) {
      case UNIFORM -> "Every key is equally likely — no policy can do much better than size/keys";
      case ZIPF -> "A few keys are very popular — both policies learn the hot set";
      case SCAN_POLLUTION -> "A one-off scan of cold keys floods the cache — watch LRU's line";
      case LOOP -> "Keys repeat in a loop slightly larger than the cache — LRU always misses";
      case SHIFTING_HOTSPOT -> "The popular keys move every 20 s — recency adapts, frequency lags";
      case TTL_BURST -> "30% of writes expire after 2 s — expirations rise under every policy";
      case FORMULARY -> "Drug-formulary lookups with a morning surge on common drugs";
      case PROVIDER_DIRECTORY -> "Provider-directory lookups; the busy region moves every 30 s";
    };
  }

  private static KeyStream uniform(Params p) {
    p.allow("keySpace");
    int keySpace = p.keySpace();
    return (rnd, op, ms) -> "key:" + rnd.nextInt(keySpace);
  }

  private static KeyStream zipf(Params p) {
    p.allow("keySpace", "zipfS");
    ZipfSampler zipf = new ZipfSampler(p.keySpace(), p.zipfS(DEFAULT_ZIPF_S));
    return (rnd, op, ms) -> "key:" + zipf.sample(rnd);
  }

  private static KeyStream scanPollution(Params p) {
    p.allow("keySpace", "zipfS", "coldKeys");
    ZipfSampler zipf = new ZipfSampler(p.keySpace(), p.zipfS(DEFAULT_ZIPF_S));
    int coldKeys = p.intParam("coldKeys", DEFAULT_COLD_KEYS, 1, MAX_KEY_SPACE);
    return (rnd, op, ms) ->
        (op & 1) == 0 ? "cold:" + (op / 2) % coldKeys : "key:" + zipf.sample(rnd);
  }

  private static KeyStream loop(Params p) {
    p.allow("loopSize", "rereadShare");
    int loopSize = p.intParam("loopSize", DEFAULT_LOOP_SIZE, 1, MAX_KEY_SPACE);
    double reread = p.share("rereadShare", DEFAULT_REREAD_SHARE);
    return (rnd, op, ms) ->
        reread > 0 && rnd.nextDouble() < reread
            ? "key:" + rnd.nextInt(loopSize)
            : "key:" + op % loopSize;
  }

  private static KeyStream shiftingHotspot(Params p) {
    p.allow("keySpace", "zipfS", "shiftBy", "shiftEverySec");
    int keySpace = p.keySpace();
    ZipfSampler zipf = new ZipfSampler(keySpace, p.zipfS(DEFAULT_ZIPF_S));
    int shiftBy = p.intParam("shiftBy", DEFAULT_SHIFT_BY, 0, MAX_KEY_SPACE);
    long everyMs = 1000L * p.intParam("shiftEverySec", DEFAULT_SHIFT_EVERY_SEC, 1, 3600);
    return (rnd, op, ms) -> {
      long offset = (ms / everyMs) * shiftBy;
      return "key:" + (zipf.sample(rnd) + offset) % keySpace;
    };
  }

  private static KeyStream formulary(Params p) {
    p.allow("zipfS");
    ZipfSampler zipf = new ZipfSampler(DRUGS, p.zipfS(FORMULARY_ZIPF_S));
    int stride = DRUGS / COMMON_DRUGS; // 200 fixed drugs spread evenly over the catalogue
    return (rnd, op, ms) -> {
      boolean surge = ms % SURGE_CYCLE_MS >= SURGE_START_MS;
      if (surge && rnd.nextDouble() < SURGE_SHARE) {
        return "drug:" + (rnd.nextInt(COMMON_DRUGS) * stride + stride / 2);
      }
      return "drug:" + zipf.sample(rnd);
    };
  }

  private static KeyStream providerDirectory(Params p) {
    p.allow("zipfS");
    ZipfSampler zipf = new ZipfSampler(PROVIDERS_PER_REGION, p.zipfS(DEFAULT_ZIPF_S));
    return (rnd, op, ms) -> {
      int hot = (int) ((ms / REGION_MOVE_MS) % REGIONS);
      int region = rnd.nextDouble() < HOT_REGION_SHARE ? hot : rnd.nextInt(REGIONS);
      return "prov:r" + region + ":" + zipf.sample(rnd);
    };
  }

  /** Validated access to the parameter overrides of one pattern. */
  private record Params(Pattern pattern, Map<String, Object> values) {

    void allow(String... names) {
      Set<String> allowed = Set.of(names);
      for (String name : values.keySet()) {
        if (!allowed.contains(name)) {
          throw new IllegalArgumentException(
              "Unknown parameter '" + name + "' for " + pattern + "; allowed: " + allowed);
        }
      }
    }

    int keySpace() {
      return intParam("keySpace", DEFAULT_KEY_SPACE, 1, MAX_KEY_SPACE);
    }

    double zipfS(double defaultValue) {
      Object value = values.get("zipfS");
      if (value == null) {
        return defaultValue;
      }
      if (!(value instanceof Number number) || !(number.doubleValue() >= 0.0)) {
        throw new IllegalArgumentException("zipfS must be a number >= 0: " + value);
      }
      double s = number.doubleValue();
      if (s > 5.0) {
        throw new IllegalArgumentException("zipfS must be <= 5: " + s);
      }
      return s;
    }

    double share(String name, double defaultValue) {
      Object value = values.get(name);
      if (value == null) {
        return defaultValue;
      }
      if (!(value instanceof Number number)
          || !(number.doubleValue() >= 0.0 && number.doubleValue() <= 1.0)) {
        throw new IllegalArgumentException(name + " must be a number in [0, 1]: " + value);
      }
      return number.doubleValue();
    }

    int intParam(String name, int defaultValue, int min, int max) {
      Object value = values.get(name);
      if (value == null) {
        return defaultValue;
      }
      if (!(value instanceof Number number)
          || number.doubleValue() != Math.rint(number.doubleValue())
          || number.doubleValue() < min
          || number.doubleValue() > max) {
        throw new IllegalArgumentException(
            name + " must be an integer in [" + min + ", " + max + "]: " + value);
      }
      return number.intValue();
    }
  }

  /**
   * Returns the logical operations per second of a simulation.
   *
   * @param opsPerSec the requested rate; 0 means unthrottled
   * @return {@code opsPerSec}, or {@value #UNTHROTTLED_RATE} when it is 0
   */
  public static int effectiveRate(int opsPerSec) {
    return opsPerSec > 0 ? opsPerSec : UNTHROTTLED_RATE;
  }

  /**
   * Converts an operation index to logical time.
   *
   * @param opIndex zero-based operation index within the phase
   * @param effectiveRate logical operations per second, at least 1
   * @return {@code opIndex x 1000 / effectiveRate}
   */
  public static long logicalMillis(long opIndex, int effectiveRate) {
    return opIndex * 1000 / effectiveRate;
  }
}
