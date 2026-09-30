package io.cachelab.server.workload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class KeyStreamsTest {

  private static final int RATE = 5_000;

  /** Draws {@code n} keys starting at op 0, with logical time at {@link #RATE}. */
  private static List<String> keys(Pattern pattern, Map<String, Object> params, long seed, int n) {
    return keys(pattern, params, seed, 0, n);
  }

  private static List<String> keys(
      Pattern pattern, Map<String, Object> params, long seed, long fromOp, int n) {
    KeyStream stream = KeyStreams.create(pattern, params);
    SplittableRandom rnd = new SplittableRandom(seed);
    List<String> keys = new ArrayList<>(n);
    for (long op = fromOp; op < fromOp + n; op++) {
      keys.add(stream.next(rnd, op, KeyStreams.logicalMillis(op, RATE)));
    }
    return keys;
  }

  @ParameterizedTest
  @EnumSource(Pattern.class)
  void sameSeedGivesTheSameKeysAndAnotherSeedDiffers(Pattern pattern) {
    List<String> a = keys(pattern, null, 42, 5_000);
    assertThat(keys(pattern, null, 42, 5_000)).isEqualTo(a);
    if (pattern != Pattern.LOOP) {
      assertThat(keys(pattern, null, 43, 5_000)).isNotEqualTo(a);
    }
  }

  @Test
  void keyFormats() {
    assertThat(keys(Pattern.UNIFORM, null, 1, 2_000)).allMatch(k -> k.matches("key:\\d{1,4}"));
    assertThat(keys(Pattern.ZIPF, null, 1, 2_000)).allMatch(k -> k.matches("key:\\d{1,4}"));
    assertThat(keys(Pattern.TTL_BURST, null, 1, 2_000)).allMatch(k -> k.matches("key:\\d{1,4}"));
    assertThat(keys(Pattern.FORMULARY, null, 1, 2_000)).allMatch(k -> k.matches("drug:\\d{1,5}"));
    assertThat(keys(Pattern.PROVIDER_DIRECTORY, null, 1, 2_000))
        .allMatch(k -> k.matches("prov:r\\d:\\d{1,4}"));
    assertThat(keys(Pattern.SCAN_POLLUTION, null, 1, 2_000))
        .allMatch(k -> k.matches("(key|cold):\\d{1,4}"));
  }

  @Test
  void uniformCoversTheKeySpaceEvenly() {
    Map<String, Integer> counts = counts(keys(Pattern.UNIFORM, null, 5, 200_000));
    assertThat(counts).hasSizeGreaterThan(9_990);
    assertThat(counts.values().stream().mapToInt(Integer::intValue).max().orElseThrow())
        .isLessThan(60); // mean 20
  }

  @Test
  void loopCyclesThroughElevenHundredKeys() {
    List<String> keys = keys(Pattern.LOOP, Map.of("rereadShare", 0), 1, 2_300);
    assertThat(keys.get(0)).isEqualTo("key:0");
    assertThat(keys.get(1_099)).isEqualTo("key:1099");
    assertThat(keys.get(1_100)).isEqualTo("key:0");
    assertThat(keys.get(2_299)).isEqualTo("key:99");
    assertThat(new HashSet<>(keys)).hasSize(1_100);
  }

  @Test
  void loopByDefaultReReadsAboutTwoPercentOfRandomLoopKeys() {
    List<String> keys = keys(Pattern.LOOP, null, 1, 100_000);
    long offCycle = 0;
    for (int i = 0; i < keys.size(); i++) {
      if (!keys.get(i).equals("key:" + i % 1_100)) {
        offCycle++;
      }
    }
    assertThat(offCycle / 100_000.0).isBetween(0.015, 0.025);
    assertThat(new HashSet<>(keys)).hasSize(1_100);
  }

  @Test
  void scanPollutionAlternatesColdSweepAndHotTraffic() {
    List<String> keys = keys(Pattern.SCAN_POLLUTION, null, 1, 20_004);
    for (int i = 0; i < keys.size(); i++) {
      if (i % 2 == 0) {
        assertThat(keys.get(i)).isEqualTo("cold:" + (i / 2) % 5_000);
      } else {
        assertThat(keys.get(i)).startsWith("key:");
      }
    }
    assertThat(keys.get(10_000)).isEqualTo("cold:0"); // the sweep restarts after 5,000 cold keys
  }

  @Test
  void zipfIsHeavilySkewed() {
    Map<String, Integer> counts = counts(keys(Pattern.ZIPF, null, 3, 200_000));
    int[] sorted = counts.values().stream().mapToInt(Integer::intValue).sorted().toArray();
    int top = sorted[sorted.length - 1];
    int median = sorted[sorted.length / 2];
    assertThat(counts.get("key:0")).isEqualTo(top); // rank 0 is the hottest
    assertThat(top).isGreaterThan(50 * Math.max(1, median));
    // s = 1 over 10,000 keys: P(rank 0) = 1 / H(10,000) ≈ 0.1020
    assertThat(top / 200_000.0).isBetween(0.095, 0.109);
  }

  @Test
  void shiftingHotspotMovesItsOffsetEveryTwentyLogicalSeconds() {
    long opsPer20s = 20L * RATE;
    Map<String, Integer> before = counts(keys(Pattern.SHIFTING_HOTSPOT, null, 9, 0, 50_000));
    Map<String, Integer> after = counts(keys(Pattern.SHIFTING_HOTSPOT, null, 9, opsPer20s, 50_000));
    assertThat(hottest(before)).isEqualTo("key:0");
    assertThat(hottest(after)).isEqualTo("key:2000");
    Map<String, Integer> later =
        counts(keys(Pattern.SHIFTING_HOTSPOT, null, 9, 5 * opsPer20s, 50_000));
    assertThat(hottest(later)).isEqualTo("key:0"); // offset 10,000 wraps around
  }

  @Test
  void formularySurgesOnCommonDrugsDuringTheLastTenSecondsOfEachFortySecondCycle() {
    Set<String> common = new HashSet<>();
    for (int i = 0; i < KeyStreams.COMMON_DRUGS; i++) {
      int stride = KeyStreams.DRUGS / KeyStreams.COMMON_DRUGS;
      common.add("drug:" + (i * stride + stride / 2));
    }
    long surgeStart = 30L * RATE;
    double quiet = share(keys(Pattern.FORMULARY, null, 2, 0, 20_000), common);
    double surge = share(keys(Pattern.FORMULARY, null, 2, surgeStart, 20_000), common);
    assertThat(quiet).isLessThan(0.05);
    assertThat(surge).isBetween(0.47, 0.56);
  }

  @Test
  void providerDirectorySendsSeventyPercentToAHotRegionThatMoves() {
    long opsPer30s = 30L * RATE;
    List<String> first = keys(Pattern.PROVIDER_DIRECTORY, null, 4, 0, 20_000);
    List<String> second = keys(Pattern.PROVIDER_DIRECTORY, null, 4, opsPer30s, 20_000);
    double r0 = first.stream().filter(k -> k.startsWith("prov:r0:")).count() / 20_000.0;
    double r1 = second.stream().filter(k -> k.startsWith("prov:r1:")).count() / 20_000.0;
    assertThat(r0).isBetween(0.70, 0.76); // 0.7 + 0.3 / 10
    assertThat(r1).isBetween(0.70, 0.76);
  }

  @Test
  void paramsOverrideDefaultsAndUnknownOrInvalidOnesAreRejected() {
    assertThat(keys(Pattern.LOOP, Map.of("loopSize", 3, "rereadShare", 0), 1, 4))
        .containsExactly("key:0", "key:1", "key:2", "key:0");
    assertThat(keys(Pattern.UNIFORM, Map.of("keySpace", 5), 1, 1_000))
        .allMatch(k -> k.matches("key:[0-4]"));
    assertThatThrownBy(() -> KeyStreams.create(Pattern.LOOP, Map.of("zipfS", 1.0)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown parameter 'zipfS'");
    assertThatThrownBy(() -> KeyStreams.create(Pattern.ZIPF, Map.of("keySpace", 0)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> KeyStreams.create(Pattern.ZIPF, Map.of("zipfS", "x")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void everyPatternHasACaption() {
    assertThat(Arrays.stream(Pattern.values()).map(KeyStreams::describe)).doesNotContainNull();
  }

  @Test
  void zipfSamplerStaysInRangeAndSimulatedDatabaseAccountsLatency() {
    ZipfSampler zipf = new ZipfSampler(3, 2.0);
    SplittableRandom rnd = new SplittableRandom(1);
    for (int i = 0; i < 10_000; i++) {
      assertThat(zipf.sample(rnd)).isBetween(0, 2);
    }
    SimulatedDatabase db = new SimulatedDatabase(7);
    assertThat(db.load("k")).isEqualTo("db:k");
    for (int i = 0; i < 9_999; i++) {
      db.load("k");
    }
    assertThat(db.calls()).isEqualTo(10_000);
    assertThat(db.meanLatencyMs()).isBetween(12.3, 12.7);
    assertThat(db.totalLatencyMs()).isBetween(5.0 * 10_000, 20.0 * 10_000);
  }

  private static Map<String, Integer> counts(List<String> keys) {
    Map<String, Integer> counts = new HashMap<>();
    keys.forEach(k -> counts.merge(k, 1, Integer::sum));
    return counts;
  }

  private static String hottest(Map<String, Integer> counts) {
    return counts.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
  }

  private static double share(List<String> keys, Set<String> set) {
    return keys.stream().filter(set::contains).count() / (double) keys.size();
  }
}
