package io.cachelab.internal;

import static io.cachelab.RemovalCause.EVICTED;
import static io.cachelab.RemovalCause.EXPIRED;
import static io.cachelab.RemovalCause.EXPLICIT;
import static io.cachelab.RemovalCause.REPLACED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cachelab.CacheStats;
import io.cachelab.EntryView;
import io.cachelab.PolicySnapshot;
import io.cachelab.PolicyType;
import io.cachelab.testing.FakeTicker;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class BoundedCacheTest {

  private final FakeTicker ticker =
      new FakeTicker(-1_000_000_000L); // negative, like nanoTime can be

  private TestCaches.Recorded cache(int capacity, PolicyType policy) {
    return TestCaches.recorded(capacity, policy, ticker);
  }

  @Nested
  class Lru {

    @Test
    void evictsTheLeastRecentlyUsedEntry() {
      var rec = cache(3, PolicyType.LRU);
      var c = rec.cache();
      c.put("a", "1");
      c.put("b", "2");
      c.put("c", "3");
      c.get("a");
      c.put("d", "4");
      assertThat(rec.keys(EVICTED)).containsExactly("b");
      assertThat(c.get("b")).isEmpty();
      assertThat(c.size()).isEqualTo(3);
      c.checkInvariants();
    }

    @Test
    void capacityOneKeepsOnlyTheNewestEntry() {
      var rec = cache(1, PolicyType.LRU);
      var c = rec.cache();
      c.put("a", "1");
      c.put("b", "2");
      assertThat(rec.keys(EVICTED)).containsExactly("a");
      assertThat(c.get("a")).isEmpty();
      assertThat(c.get("b")).contains("2");
      assertThat(c.size()).isEqualTo(1);
    }

    @Test
    void replaceNeverEvictsAndCountsAsAccess() {
      var rec = cache(2, PolicyType.LRU);
      var c = rec.cache();
      c.put("a", "1");
      c.put("b", "2");
      c.put("a", "1b"); // full cache, existing key: replace only
      assertThat(rec.keys(EVICTED)).isEmpty();
      assertThat(rec.events()).containsExactly(new TestCaches.Event("a", "1", REPLACED));
      assertThat(c.get("a")).contains("1b");
      c.put("c", "3"); // "a" was touched by the replace, so "b" is the LRU victim
      assertThat(rec.keys(EVICTED)).containsExactly("b");
    }
  }

  @Nested
  class Lfu {

    @Test
    void evictsTheLeastFrequentBreakingTiesByRecency() {
      var rec = cache(3, PolicyType.LFU);
      var c = rec.cache();
      c.put("a", "1");
      c.put("b", "2");
      c.put("c", "3");
      c.get("a");
      c.get("a");
      c.get("c"); // a=3, c=2, b=1
      c.put("d", "4");
      assertThat(rec.keys(EVICTED)).containsExactly("b");
      c.get("d"); // d=2 and c=2; c reached 2 earlier, so c is less recent
      c.put("e", "5");
      assertThat(rec.keys(EVICTED)).containsExactly("b", "c");
      c.checkInvariants();
    }

    @Test
    void emptiedBucketsDisappear() {
      var rec = cache(2, PolicyType.LFU);
      var c = rec.cache();
      c.put("a", "1");
      c.get("a");
      c.get("a");
      assertThat(((LfuPolicy<?, ?>) policyOf(c)).bucketFrequencies()).containsExactly(3L);
      c.checkInvariants();
    }

    @Test
    void ttlRemovalOfTheLastMinimumFrequencyNodeDoesNotBreakEviction() {
      var rec = cache(2, PolicyType.LFU);
      var c = rec.cache();
      c.put("a", "1", Duration.ofSeconds(1)); // the only frequency-1 entry
      c.put("b", "2");
      c.get("b");
      c.get("b"); // b=3
      ticker.advance(Duration.ofSeconds(2));
      c.put("c", "3"); // full: a is purged as EXPIRED; b must survive
      assertThat(rec.keys(EXPIRED)).containsExactly("a");
      assertThat(rec.keys(EVICTED)).isEmpty();
      c.checkInvariants();
      c.put("d", "4"); // now evict the minimum: c (freq 1), never b (freq 3)
      assertThat(rec.keys(EVICTED)).containsExactly("c");
      assertThat(c.get("b")).contains("2");
      c.checkInvariants();
    }
  }

  @Nested
  class Ttl {

    @ParameterizedTest
    @EnumSource(names = {"LRU", "LFU"})
    void anExpiredReadIsOneMissAndOneExpiration(PolicyType policy) {
      var rec = cache(4, policy);
      var c = rec.cache();
      c.put("k", "v", Duration.ofSeconds(1));
      ticker.advance(Duration.ofMillis(999));
      assertThat(c.get("k")).contains("v");
      ticker.advance(Duration.ofMillis(1));
      assertThat(c.get("k")).isEmpty();
      CacheStats s = c.stats();
      assertThat(s.hitCount()).isEqualTo(1);
      assertThat(s.missCount()).isEqualTo(1);
      assertThat(s.expirationCount()).isEqualTo(1);
      assertThat(rec.events()).containsExactly(new TestCaches.Event("k", "v", EXPIRED));
      assertThat(c.size()).isZero();
      c.checkInvariants();
    }

    @ParameterizedTest
    @EnumSource(names = {"LRU", "LFU"})
    void expiredEntriesArePurgedBeforeALiveEntryIsEvicted(PolicyType policy) {
      var rec = cache(2, policy);
      var c = rec.cache();
      c.put("old", "1", Duration.ofSeconds(1));
      c.put("live", "2");
      ticker.advance(Duration.ofSeconds(5));
      c.put("new", "3");
      assertThat(rec.keys(EXPIRED)).containsExactly("old");
      assertThat(rec.keys(EVICTED)).isEmpty();
      assertThat(c.get("live")).contains("2");
      assertThat(c.stats().evictionCount()).isZero();
      c.checkInvariants();
    }

    @ParameterizedTest
    @EnumSource(names = {"LRU", "LFU"})
    void replacingResetsTheTtl(PolicyType policy) {
      var rec = cache(4, policy);
      var c = rec.cache();
      c.put("k", "v1", Duration.ofSeconds(1));
      ticker.advance(Duration.ofMillis(800));
      c.put("k", "v2", Duration.ofSeconds(1)); // new deadline: 1.8 s
      ticker.advance(Duration.ofMillis(800));
      assertThat(c.get("k")).contains("v2");
      assertThat(c.ttlRemaining("k")).contains(Duration.ofMillis(200));
      ticker.advance(Duration.ofMillis(200));
      assertThat(c.get("k")).isEmpty();
      assertThat(rec.events())
          .containsExactly(
              new TestCaches.Event("k", "v1", REPLACED), new TestCaches.Event("k", "v2", EXPIRED));
    }

    @ParameterizedTest
    @EnumSource(names = {"LRU", "LFU"})
    void replacingWithoutTtlUsesTheDefaultAndCancelsTheOldDeadline(PolicyType policy) {
      var c = TestCaches.recorded(4, policy, ticker, Duration.ofSeconds(10).toNanos()).cache();
      c.put("k", "v", Duration.ofSeconds(1));
      c.put("k", "v2"); // default TTL (10 s) now applies
      ticker.advance(Duration.ofSeconds(5));
      c.sweep();
      assertThat(c.get("k")).contains("v2");
      ticker.advance(Duration.ofSeconds(5));
      assertThat(c.get("k")).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(names = {"LRU", "LFU"})
    void theSweeperRemovesExpiredEntriesWithoutAnyRead(PolicyType policy) {
      var rec = cache(10, policy);
      var c = rec.cache();
      for (int i = 0; i < 5; i++) {
        c.put("t" + i, "v", Duration.ofSeconds(1));
      }
      c.put("forever", "v");
      ticker.advance(Duration.ofSeconds(1));
      c.sweep();
      assertThat(c.size()).isEqualTo(1);
      assertThat(rec.keys(EXPIRED)).hasSize(5);
      assertThat(c.stats().expirationCount()).isEqualTo(5);
      assertThat(c.stats().missCount()).isZero(); // sweeping is not a lookup
      c.checkInvariants();
    }

    @Test
    void expiryIsIdenticalUnderEveryPolicy() {
      // Capacity is large enough that nothing is ever evicted, so any difference in expiry
      // behaviour could only come from the policy — and there must be none (ADR-002).
      List<List<String>> expiredPerPolicy =
          List.of(PolicyType.LRU, PolicyType.LFU).stream()
              .map(
                  policy -> {
                    FakeTicker t = new FakeTicker();
                    var rec = TestCaches.recorded(1_000, policy, t, CacheSettings.NO_TTL);
                    var c = rec.cache();
                    java.util.SplittableRandom rnd = new java.util.SplittableRandom(42);
                    for (int i = 0; i < 2_000; i++) {
                      String key = "k" + rnd.nextInt(50);
                      if (rnd.nextInt(3) == 0) {
                        c.put(key, "v" + i, Duration.ofMillis(50 + rnd.nextInt(500)));
                      } else {
                        c.get(key);
                      }
                      t.advance(Duration.ofMillis(rnd.nextInt(20)));
                      if (i % 25 == 0) {
                        c.sweep();
                      }
                    }
                    assertThat(c.stats().evictionCount()).isZero();
                    return rec.keys(EXPIRED);
                  })
              .toList();
      assertThat(expiredPerPolicy.get(0)).isNotEmpty().isEqualTo(expiredPerPolicy.get(1));
    }

    @Test
    void ttlRemainingIsEmptyWhenMissingExpiredOrUnbounded() {
      var c = cache(4, PolicyType.LRU).cache();
      c.put("bounded", "v", Duration.ofSeconds(3));
      c.put("forever", "v");
      assertThat(c.ttlRemaining("bounded")).contains(Duration.ofSeconds(3));
      assertThat(c.ttlRemaining("forever")).isEmpty();
      assertThat(c.ttlRemaining("missing")).isEmpty();
      ticker.advance(Duration.ofSeconds(3));
      assertThat(c.ttlRemaining("bounded")).isEmpty();
      assertThat(c.stats().requestCount()).isZero(); // a TTL query is not a lookup
    }

    @Test
    void removingAnExpiredEntryReportsExpiredAndReturnsFalse() {
      var rec = cache(4, PolicyType.LFU);
      var c = rec.cache();
      c.put("k", "v", Duration.ofSeconds(1));
      ticker.advance(Duration.ofSeconds(2));
      assertThat(c.remove("k")).isFalse();
      assertThat(rec.events()).containsExactly(new TestCaches.Event("k", "v", EXPIRED));
    }

    @Test
    void puttingOverAnExpiredEntryIsAFreshInsertNotAReplace() {
      var rec = cache(1, PolicyType.LRU);
      var c = rec.cache();
      c.put("k", "old", Duration.ofSeconds(1));
      ticker.advance(Duration.ofSeconds(2));
      c.put("k", "new");
      assertThat(rec.events()).containsExactly(new TestCaches.Event("k", "old", EXPIRED));
      assertThat(c.get("k")).contains("new");
    }

    @Test
    void rejectsNonPositiveTtl() {
      var c = cache(4, PolicyType.LRU).cache();
      assertThatThrownBy(() -> c.put("k", "v", Duration.ZERO))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("ttl must be positive");
      assertThatThrownBy(() -> c.put("k", "v", Duration.ofMillis(-1)))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enormousTtlsAreCappedNotOverflowed() {
      var c = cache(4, PolicyType.LRU).cache();
      c.put("k", "v", Duration.ofSeconds(Long.MAX_VALUE));
      ticker.advance(Duration.ofDays(365 * 100));
      assertThat(c.get("k")).contains("v");
      assertThat(c.ttlRemaining("k")).isPresent();
    }
  }

  @Nested
  class Semantics {

    @Test
    void sizeNeverExceedsTheMaximum() {
      var c = cache(5, PolicyType.LFU).cache();
      for (int i = 0; i < 1_000; i++) {
        c.put("k" + (i % 37), "v");
        assertThat(c.size()).isLessThanOrEqualTo(5);
      }
      c.checkInvariants();
    }

    @Test
    void rejectsNulls() {
      var c = cache(4, PolicyType.LRU).cache();
      assertThatThrownBy(() -> c.get(null)).isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> c.put(null, "v")).isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> c.put("k", null)).isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> c.put("k", "v", null)).isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> c.remove(null)).isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> c.ttlRemaining(null)).isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> c.getOrLoad("k", null)).isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> c.switchPolicy(null)).isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> c.entries(-1)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> c.policySnapshot(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void removeAndClearReportExplicitRemovals() {
      var rec = cache(4, PolicyType.LRU);
      var c = rec.cache();
      c.put("a", "1");
      c.put("b", "2");
      c.put("c", "3");
      assertThat(c.remove("a")).isTrue();
      assertThat(c.remove("a")).isFalse();
      c.clear();
      assertThat(rec.keys(EXPLICIT)).containsExactlyInAnyOrder("a", "b", "c");
      assertThat(c.size()).isZero();
      c.put("d", "4");
      c.checkInvariants();
    }

    @Test
    void statisticsCountEveryOperation() {
      var c = cache(2, PolicyType.LRU).cache();
      c.put("a", "1");
      c.put("b", "2");
      c.put("c", "3"); // evicts a
      c.get("a");
      c.get("b");
      c.get("c");
      CacheStats s = c.stats();
      assertThat(s.putCount()).isEqualTo(3);
      assertThat(s.evictionCount()).isEqualTo(1);
      assertThat(s.hitCount()).isEqualTo(2);
      assertThat(s.missCount()).isEqualTo(1);
    }

    @Test
    void entriesAreInPolicyOrderWithTtlsAndSkipExpiredOnes() {
      var c = cache(5, PolicyType.LFU).cache();
      c.put("a", "1");
      c.put("b", "2", Duration.ofSeconds(10));
      c.put("gone", "3", Duration.ofSeconds(1));
      c.get("b");
      c.get("gone");
      c.get("gone");
      ticker.advance(Duration.ofSeconds(1));
      List<EntryView<String>> entries = c.entries(10);
      assertThat(entries)
          .containsExactly(
              new EntryView<>("b", 2, Optional.of(Duration.ofSeconds(9))),
              new EntryView<>("a", 1, Optional.empty()));
      assertThat(c.entries(1)).hasSize(1);
      assertThat(c.entries(0)).isEmpty();
    }

    @Test
    void lruSnapshotsReportFrequencyZero() {
      var c = cache(3, PolicyType.LRU).cache();
      c.put("a", "1");
      c.put("b", "2");
      c.get("a");
      assertThat(c.policySnapshot(5))
          .isEqualTo(
              new PolicySnapshot<>(
                  PolicyType.LRU,
                  List.of(new PolicySnapshot.Entry<>("a", 0), new PolicySnapshot.Entry<>("b", 0))));
    }
  }

  @Nested
  class PolicySwitch {

    @Test
    void lruToLfuKeepsEntriesAndTtlsAndThenEvictsByFrequency() {
      var rec = cache(3, PolicyType.LRU);
      var c = rec.cache();
      c.put("a", "1", Duration.ofSeconds(30));
      c.put("b", "2");
      c.put("c", "3");
      c.switchPolicy(PolicyType.LFU);
      c.checkInvariants();
      assertThat(c.policyType()).isEqualTo(PolicyType.LFU);
      assertThat(c.size()).isEqualTo(3);
      assertThat(c.ttlRemaining("a")).contains(Duration.ofSeconds(30));
      c.get("a");
      c.get("c"); // a=2, c=2, b=1
      c.put("d", "4");
      assertThat(rec.keys(EVICTED)).containsExactly("b");
      c.checkInvariants();
    }

    @Test
    void lfuFrequenciesSurviveARoundTripThroughLru() {
      var c = cache(3, PolicyType.LFU).cache();
      c.put("hot", "1");
      c.put("cold", "2");
      for (int i = 0; i < 5; i++) {
        c.get("hot");
      }
      c.switchPolicy(PolicyType.LRU);
      c.switchPolicy(PolicyType.LFU);
      c.checkInvariants();
      assertThat(c.policySnapshot(2).entries())
          .containsExactly(
              new PolicySnapshot.Entry<>("hot", 6), new PolicySnapshot.Entry<>("cold", 1));
    }

    @Test
    void lfuToLruOrdersByRecency() {
      var rec = cache(3, PolicyType.LFU);
      var c = rec.cache();
      c.put("a", "1");
      c.put("b", "2");
      c.put("c", "3");
      c.get("a");
      c.get("a");
      c.get("b"); // recency: a is older than b; c older than both of their last reads
      c.switchPolicy(PolicyType.LRU);
      c.checkInvariants();
      c.put("d", "4");
      assertThat(rec.keys(EVICTED)).containsExactly("c");
    }

    @Test
    void switchingToTheSamePolicyIsANoOp() {
      var c = cache(3, PolicyType.LRU).cache();
      EvictionPolicy<String, String> before = policyOf(c);
      c.switchPolicy(PolicyType.LRU);
      assertThat(policyOf(c)).isSameAs(before);
    }
  }

  @SuppressWarnings("unchecked")
  private static EvictionPolicy<String, String> policyOf(BoundedCache<String, String> c) {
    try {
      var field = BoundedCache.class.getDeclaredField("policy");
      field.setAccessible(true);
      return (EvictionPolicy<String, String>) field.get(c);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }
}
