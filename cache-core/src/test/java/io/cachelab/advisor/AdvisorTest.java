package io.cachelab.advisor;

import static org.assertj.core.api.Assertions.assertThat;

import io.cachelab.PolicyType;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AdvisorTest {

  private final AtomicLong clockMs = new AtomicLong();

  @Test
  void shadowCountsHitsInOneSecondWindows() {
    ShadowCache<String> shadow = new ShadowCache<>(2, PolicyType.LRU, clockMs::get, 10_000);
    assertThat(shadow.access("a")).isFalse();
    assertThat(shadow.access("a")).isTrue();
    clockMs.set(5_000);
    assertThat(shadow.access("a")).isTrue();
    assertThat(shadow.windowRequests(1)).isEqualTo(1);
    assertThat(shadow.windowRequests(10)).isEqualTo(3);
    assertThat(shadow.windowHitRate(10)).isEqualTo(2.0 / 3);
    clockMs.set(70_000);
    assertThat(shadow.windowRequests(60)).isZero(); // old buckets aged out
    assertThat(shadow.totals()).isEqualTo(new ShadowCache.Result(2, 1, 0));
    shadow.access("b");
    shadow.access("c"); // capacity 2: evicts a
    assertThat(shadow.totals().evictions()).isEqualTo(1);
    shadow.resetWindow();
    assertThat(shadow.windowRequests(60)).isZero();
  }

  @Test
  void anLfuDecayShadowDecaysOnItsClock() {
    ShadowCache<String> decaying = new ShadowCache<>(2, PolicyType.LFU_DECAY, clockMs::get, 10_000);
    ShadowCache<String> plain = new ShadowCache<>(2, PolicyType.LFU, clockMs::get, 10_000);
    for (ShadowCache<String> s : List.of(decaying, plain)) {
      for (int i = 0; i < 8; i++) {
        s.access("old"); // frequency 8
      }
      s.access("new");
      s.access("new"); // frequency 2
    }
    clockMs.set(40_000); // four decays for the decaying shadow: old 8 -> 1, new 2 -> 1
    for (ShadowCache<String> s : List.of(decaying, plain)) {
      s.access("new"); // decaying: new 2 > old 1; plain: new 3 < old 8
      s.access("x"); // evicts the least frequent
    }
    assertThat(decaying.access("new")).isTrue();
    assertThat(decaying.access("old")).isFalse(); // decay let the new favourite win
    assertThat(plain.access("old")).isTrue(); // plain LFU clings to the old favourite
  }

  @Test
  void theRecorderKeepsTheNewestKeysInOrder() {
    KeyRecorder<String> recorder = new KeyRecorder<>(3);
    for (String k : List.of("a", "b", "c", "d", "e")) {
      recorder.onAccess(k, false);
    }
    assertThat(recorder.snapshot()).containsExactly("c", "d", "e");
    assertThat(recorder.totalRecorded()).isEqualTo(5);
    recorder.clear();
    assertThat(recorder.snapshot()).isEmpty();
  }

  @Test
  void recommendsLfuWhenAScanPollutesLru() {
    AtomicReference<PolicyType> current = new AtomicReference<>(PolicyType.LRU);
    PolicyAdvisor<String> advisor = new PolicyAdvisor<>(100, current::get, clockMs::get, 10_000);
    SplittableRandom rnd = new SplittableRandom(1);
    long cold = 0;
    for (int i = 0; i < 62_000; i++) { // 31 s of hot keys interleaved with a cold scan
      clockMs.set(i / 2); // 2,000 lookups per second
      advisor.onAccess(i % 2 == 0 ? "hot:" + rnd.nextInt(80) : "cold:" + cold++, false);
    }
    Optional<Recommendation> rec = advisor.evaluate();
    assertThat(rec).isPresent();
    assertThat(rec.get().current()).isEqualTo(PolicyType.LRU);
    assertThat(rec.get().recommended()).isIn(PolicyType.LFU, PolicyType.LFU_DECAY);
    assertThat(rec.get().expectedGainPts()).isGreaterThan(PolicyAdvisor.THRESHOLD_PTS);
    assertThat(rec.get().windowSec()).isEqualTo(30);

    current.set(rec.get().recommended()); // a person applied it
    advisor.reset();
    assertThat(advisor.recommendation()).isEmpty();
    assertThat(advisor.evaluate()).isEmpty(); // needs a fresh full window
  }

  @Test
  void staysQuietWithoutAFullWindowOrAClearWinner() {
    PolicyAdvisor<String> advisor =
        new PolicyAdvisor<>(100, () -> PolicyType.LRU, clockMs::get, 10_000);
    for (int i = 0; i < 20_000; i++) {
      clockMs.set(i); // only 20 s of evidence
      advisor.onAccess("k" + (i % 50), false); // fits entirely: every policy hits ~100 %
    }
    assertThat(advisor.evaluate()).isEmpty(); // window not full yet
    for (int i = 20_000; i < 40_000; i++) {
      clockMs.set(i);
      advisor.onAccess("k" + (i % 50), false);
    }
    assertThat(advisor.evaluate()).isEmpty(); // full window, but no policy is > 3 points better
    assertThat(advisor.windowHitRates()).containsOnlyKeys(PolicyType.values());
  }
}
