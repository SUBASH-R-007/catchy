package io.cachelab.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.cachelab.PolicySnapshot;
import io.cachelab.PolicyType;
import io.cachelab.RemovalCause;
import io.cachelab.testing.FakeTicker;
import io.cachelab.testing.ReferenceLfu;
import io.cachelab.testing.ReferenceLru;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * Differential tests (SPEC 4.3, Gate 2): the engine must match an obviously-correct reference model
 * on 10,000 random sequences of 200 operations, for LRU and for LFU. Every step compares hits,
 * evictions and removals, and verifies the structural invariants.
 */
class DifferentialTest {

  private static final int SEQUENCES = 10_000;
  private static final int OPS = 200;

  /** Adapter so one driver serves both reference models. */
  private interface Model {
    boolean get(String key);

    String put(String key);

    boolean remove(String key);

    List<String> snapshotOrder();
  }

  @Test
  void lruMatchesTheReferenceModel() {
    for (int seq = 0; seq < SEQUENCES; seq++) {
      SplittableRandom rnd = new SplittableRandom(seq);
      int capacity = 1 + rnd.nextInt(8);
      ReferenceLru<String> ref = new ReferenceLru<>(capacity);
      run(seq, rnd, capacity, PolicyType.LRU, lruModel(ref));
    }
  }

  @Test
  void lfuMatchesTheReferenceModel() {
    for (int seq = 0; seq < SEQUENCES; seq++) {
      SplittableRandom rnd = new SplittableRandom(1_000_000L + seq);
      int capacity = 1 + rnd.nextInt(8);
      ReferenceLfu<String> ref = new ReferenceLfu<>(capacity);
      run(seq, rnd, capacity, PolicyType.LFU, lfuModel(ref));
    }
  }

  private static void run(
      int seq, SplittableRandom rnd, int capacity, PolicyType policy, Model model) {
    TestCaches.Recorded rec = TestCaches.recorded(capacity, policy, new FakeTicker());
    BoundedCache<String, String> cache = rec.cache();
    int keySpace = capacity + 1 + rnd.nextInt(capacity * 2 + 1);
    for (int op = 0; op < OPS; op++) {
      String key = "k" + rnd.nextInt(keySpace);
      int dice = rnd.nextInt(100);
      String where = "seq " + seq + " op " + op + " (" + policy + ", cap " + capacity + ")";
      int evictionsBefore = rec.keys(RemovalCause.EVICTED).size();
      String expectedEvicted = null;
      if (dice < 60) { // cache-aside: get, and load on a miss
        boolean hit = cache.get(key).isPresent();
        assertThat(hit).as(where + " get " + key).isEqualTo(model.get(key));
        if (!hit) {
          expectedEvicted = model.put(key);
          cache.put(key, "v" + op);
        }
      } else if (dice < 75) { // plain get
        assertThat(cache.get(key).isPresent()).as(where + " get").isEqualTo(model.get(key));
      } else if (dice < 90) { // put: insert or replace
        expectedEvicted = model.put(key);
        cache.put(key, "v" + op);
      } else { // explicit remove
        assertThat(cache.remove(key)).as(where + " remove").isEqualTo(model.remove(key));
      }
      List<String> evicted = rec.keys(RemovalCause.EVICTED);
      List<String> newlyEvicted = evicted.subList(evictionsBefore, evicted.size());
      assertThat(newlyEvicted)
          .as(where + " eviction")
          .isEqualTo(expectedEvicted == null ? List.of() : List.of(expectedEvicted));
      cache.checkInvariants();
    }
    PolicySnapshot<String> snapshot = cache.policySnapshot(capacity);
    assertThat(snapshot.entries().stream().map(PolicySnapshot.Entry::key).toList())
        .as("final order, seq " + seq)
        .isEqualTo(model.snapshotOrder());
  }

  private static Model lruModel(ReferenceLru<String> ref) {
    return new Model() {
      public boolean get(String key) {
        return ref.get(key);
      }

      public String put(String key) {
        return ref.put(key);
      }

      public boolean remove(String key) {
        return ref.remove(key);
      }

      public List<String> snapshotOrder() {
        return ref.mostRecentFirst();
      }
    };
  }

  private static Model lfuModel(ReferenceLfu<String> ref) {
    return new Model() {
      public boolean get(String key) {
        return ref.get(key);
      }

      public String put(String key) {
        return ref.put(key);
      }

      public boolean remove(String key) {
        return ref.remove(key);
      }

      public List<String> snapshotOrder() {
        return ref.snapshotOrder();
      }
    };
  }
}
