package io.cachelab.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.cachelab.PolicySnapshot;
import io.cachelab.PolicyType;
import io.cachelab.RemovalCause;
import io.cachelab.testing.FakeTicker;
import io.cachelab.testing.ReferenceLfu;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class LfuDecayPolicyTest {

  private final LfuDecayPolicy<String, String> policy = new LfuDecayPolicy<>();
  private final Map<String, Node<String, String>> nodes = new HashMap<>();
  private long tick;

  private void insert(String key) {
    Node<String, String> n = new Node<>(key, "v");
    n.lastAccess = ++tick;
    nodes.put(key, n);
    policy.onInsert(n);
  }

  private void access(String key, int times) {
    for (int i = 0; i < times; i++) {
      Node<String, String> n = nodes.get(key);
      n.lastAccess = ++tick;
      policy.onAccess(n);
    }
  }

  private List<String> drainVictims() {
    List<String> order = new ArrayList<>();
    Node<String, String> v;
    while ((v = policy.pollVictim()) != null) {
      order.add(v.key);
    }
    return order;
  }

  @Test
  void intrusiveMergeKeepsMostRecentFirst() {
    IntrusiveList<String, String> a = new IntrusiveList<>();
    IntrusiveList<String, String> b = new IntrusiveList<>();
    long[] aTicks = {9, 6, 2};
    long[] bTicks = {8, 7, 3, 1};
    for (long t : aTicks) {
      Node<String, String> n = new Node<>("a" + t, "v");
      n.lastAccess = t;
      a.linkLast(n);
    }
    for (long t : bTicks) {
      Node<String, String> n = new Node<>("b" + t, "v");
      n.lastAccess = t;
      b.linkLast(n);
    }
    a.mergeByRecency(b);
    a.checkInvariants("merged");
    b.checkInvariants("drained");
    List<Long> order = new ArrayList<>();
    a.forEach(n -> order.add(n.lastAccess));
    assertThat(order).containsExactly(9L, 8L, 7L, 6L, 3L, 2L, 1L);
    assertThat(b.isEmpty()).isTrue();
  }

  @Test
  void decayHalvesEveryFrequencyWithAFloorOfOne() {
    insert("one");
    insert("two");
    insert("three");
    insert("nine");
    access("two", 1);
    access("three", 2);
    access("nine", 8);
    policy.decay();
    policy.checkInvariants();
    assertThat(policy.bucketFrequencies()).containsExactly(1L, 4L);
    assertThat(nodes.get("one").frequency).isEqualTo(1);
    assertThat(nodes.get("two").frequency).isEqualTo(1);
    assertThat(nodes.get("three").frequency).isEqualTo(1);
    assertThat(nodes.get("nine").frequency).isEqualTo(4);
    assertThat(policy.type()).isEqualTo(PolicyType.LFU_DECAY);
  }

  @Test
  void mergedBucketsEvictLeastRecentFirst() {
    insert("a"); // freq 1, tick 1
    insert("b");
    insert("c");
    access("b", 1); // freq 2
    access("c", 2); // freq 3
    access("a", 2); // freq 3, most recent
    // after decay all are freq 1; eviction is by recency: b (last touched tick 4), c, a
    policy.decay();
    policy.checkInvariants();
    assertThat(drainVictims()).containsExactly("b", "c", "a");
  }

  @Test
  void decayLetsNewPopularityOvertakeOldPopularity() {
    insert("old");
    access("old", 15); // 16
    insert("new");
    access("new", 5); // 6
    policy.decay(); // old 8, new 3
    policy.decay(); // old 4, new 1
    access("new", 6); // new 7
    assertThat(policy.snapshot(2).entries())
        .containsExactly(
            new PolicySnapshot.Entry<>("new", 7), new PolicySnapshot.Entry<>("old", 4));
    policy.checkInvariants();
  }

  @Test
  void decayOnAnEmptyPolicyIsHarmless() {
    policy.decay();
    policy.checkInvariants();
    assertThat(policy.pollVictim()).isNull();
  }

  @Test
  void evictionOrderMatchesTheReferenceModelWithDecay() {
    for (int seq = 0; seq < 2_000; seq++) {
      SplittableRandom rnd = new SplittableRandom(seq);
      int capacity = 1 + rnd.nextInt(10);
      FakeTicker ticker = new FakeTicker();
      TestCaches.Recorded rec = TestCaches.recorded(capacity, PolicyType.LFU_DECAY, ticker);
      BoundedCache<String, String> cache = rec.cache();
      ReferenceLfu<String> ref = new ReferenceLfu<>(capacity);
      int keySpace = capacity + 1 + rnd.nextInt(capacity * 2 + 1);
      for (int op = 0; op < 200; op++) {
        String key = "k" + rnd.nextInt(keySpace);
        int before = rec.keys(RemovalCause.EVICTED).size();
        String expected = null;
        if (!cache.get(key).isPresent()) {
          assertThat(ref.get(key)).as("seq %d op %d get %s", seq, op, key).isFalse();
          expected = ref.put(key);
          cache.put(key, "v");
        } else {
          assertThat(ref.get(key)).as("seq %d op %d get %s", seq, op, key).isTrue();
        }
        List<String> evicted = rec.keys(RemovalCause.EVICTED);
        assertThat(evicted.subList(before, evicted.size()))
            .as("seq %d op %d eviction", seq, op)
            .isEqualTo(expected == null ? List.of() : List.of(expected));
        if (rnd.nextInt(20) == 0) { // the engine decays once the interval has elapsed
          ticker.advance(Duration.ofSeconds(10));
          cache.sweep();
          ref.decay();
          cache.checkInvariants();
        }
      }
      assertThat(
              cache.policySnapshot(capacity).entries().stream()
                  .map(PolicySnapshot.Entry::key)
                  .toList())
          .as("final order seq %d", seq)
          .isEqualTo(ref.snapshotOrder());
    }
  }

  @Test
  void theEngineDecaysOnlyAfterTheDecayInterval() {
    FakeTicker ticker = new FakeTicker();
    BoundedCache<String, String> cache =
        TestCaches.recorded(4, PolicyType.LFU_DECAY, ticker).cache();
    cache.put("k", "v");
    for (int i = 0; i < 7; i++) {
      cache.get("k"); // freq 8
    }
    ticker.advance(Duration.ofSeconds(9));
    cache.sweep();
    assertThat(cache.policySnapshot(1).entries().get(0).frequency()).isEqualTo(8);
    ticker.advance(Duration.ofSeconds(1));
    cache.sweep();
    assertThat(cache.policySnapshot(1).entries().get(0).frequency()).isEqualTo(4);
  }

  @Test
  void measuresTheDecayPauseAt10kAnd100kEntries() {
    for (int size : new int[] {10_000, 100_000}) {
      LfuDecayPolicy<Integer, Integer> p = new LfuDecayPolicy<>();
      List<Node<Integer, Integer>> all = new ArrayList<>(size);
      SplittableRandom rnd = new SplittableRandom(size);
      long t = 0;
      for (int i = 0; i < size; i++) {
        Node<Integer, Integer> n = new Node<>(i, i);
        n.lastAccess = ++t;
        p.onInsert(n);
        all.add(n);
      }
      for (int i = 0; i < size * 5; i++) { // skewed accesses: many distinct frequencies
        Node<Integer, Integer> n = all.get((int) (Math.abs(rnd.nextGaussian()) * size / 8) % size);
        n.lastAccess = ++t;
        p.onAccess(n);
      }
      int bucketsBefore = p.bucketFrequencies().size();
      for (int warm = 0; warm < 3; warm++) { // let the JIT compile the merge path
        p.decay();
      }
      long start = System.nanoTime();
      p.decay();
      long pauseMicros = (System.nanoTime() - start) / 1_000;
      p.checkInvariants();
      System.out.printf(
          "LFU_DECAY pause: %,d entries, %d buckets before decays -> %,d us%n",
          size, bucketsBefore, pauseMicros);
      assertThat(p.size()).isEqualTo(size);
    }
  }
}
