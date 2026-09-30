package io.cachelab.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cachelab.PolicySnapshot;
import io.cachelab.PolicyType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LfuPolicyTest {

  private LfuPolicy<String, String> policy;
  private final Map<String, Node<String, String>> nodes = new HashMap<>();
  private long tick;

  @BeforeEach
  void setUp() {
    policy = new LfuPolicy<>();
    nodes.clear();
    tick = 0;
  }

  private Node<String, String> insert(String key) {
    Node<String, String> n = new Node<>(key, "v-" + key);
    n.lastAccess = ++tick;
    nodes.put(key, n);
    policy.onInsert(n);
    policy.checkInvariants();
    return n;
  }

  private void access(String key, int times) {
    for (int i = 0; i < times; i++) {
      Node<String, String> n = nodes.get(key);
      n.lastAccess = ++tick;
      policy.onAccess(n);
      policy.checkInvariants();
    }
  }

  private List<String> drainVictims() {
    List<String> order = new ArrayList<>();
    Node<String, String> v;
    while ((v = policy.pollVictim()) != null) {
      order.add(v.key);
      policy.checkInvariants();
    }
    return order;
  }

  @Test
  void newEntriesStartAtFrequencyOne() {
    Node<String, String> a = insert("a");
    assertThat(a.frequency).isEqualTo(1);
    assertThat(policy.bucketFrequencies()).containsExactly(1L);
    assertThat(policy.type()).isEqualTo(PolicyType.LFU);
  }

  @Test
  void evictsLowestFrequencyFirst() {
    insert("a");
    insert("b");
    insert("c");
    access("a", 2);
    access("b", 1);
    assertThat(drainVictims()).containsExactly("c", "b", "a");
  }

  @Test
  void tiesAreBrokenByLeastRecentlyUsed() {
    insert("a");
    insert("b");
    insert("c");
    access("b", 1);
    access("a", 1); // a and b both at 2; b reached 2 first, so b is less recent
    assertThat(drainVictims()).containsExactly("c", "b", "a");
  }

  @Test
  void emptiedBucketsAreDeleted() {
    insert("a");
    access("a", 1);
    assertThat(policy.bucketFrequencies()).containsExactly(2L);
    access("a", 3);
    assertThat(policy.bucketFrequencies()).containsExactly(5L);
  }

  @Test
  void accessCreatesTheNextBucketInPlace() {
    insert("a");
    insert("b");
    access("b", 2); // buckets: 1{a}, 3{b}
    assertThat(policy.bucketFrequencies()).containsExactly(1L, 3L);
    access("a", 1); // a moves to a new bucket 2, between 1 and 3; bucket 1 is deleted
    assertThat(policy.bucketFrequencies()).containsExactly(2L, 3L);
    insert("c");
    assertThat(policy.bucketFrequencies()).containsExactly(1L, 2L, 3L);
  }

  @Test
  void removingTheLastMinimumFrequencyNodeKeepsEvictionCorrect() {
    // A minFreq integer would still point at frequency 1 here; the bucket list cannot go stale.
    Node<String, String> a = insert("a");
    insert("b");
    access("b", 2);
    policy.onRemove(a); // e.g. a expired via TTL
    policy.checkInvariants();
    assertThat(policy.bucketFrequencies()).containsExactly(3L);
    assertThat(policy.pollVictim().key).isEqualTo("b");
    assertThat(policy.pollVictim()).isNull();
  }

  @Test
  void newEntryAfterMinimumBucketRemovalIsTheNextVictim() {
    Node<String, String> a = insert("a");
    insert("b");
    access("b", 4);
    policy.onRemove(a);
    insert("c");
    assertThat(drainVictims()).containsExactly("c", "b");
  }

  @Test
  void snapshotIsHighestFrequencyFirstThenMostRecent() {
    insert("a");
    insert("b");
    insert("c");
    access("a", 3);
    access("c", 1);
    PolicySnapshot<String> snapshot = policy.snapshot(10);
    assertThat(snapshot.type()).isEqualTo(PolicyType.LFU);
    assertThat(snapshot.entries())
        .containsExactly(
            new PolicySnapshot.Entry<>("a", 4),
            new PolicySnapshot.Entry<>("c", 2),
            new PolicySnapshot.Entry<>("b", 1));
    assertThat(policy.snapshot(2).entries()).hasSize(2);
    assertThat(policy.snapshot(0).entries()).isEmpty();
  }

  @Test
  void rebuildKeepsFrequenciesAndRecencyTieBreaks() {
    List<Node<String, String>> leastRecentFirst = new ArrayList<>();
    String[] keys = {"a", "b", "c", "d"};
    long[] freqs = {2, 1, 2, 0};
    for (int i = 0; i < keys.length; i++) {
      Node<String, String> n = new Node<>(keys[i], "v");
      n.frequency = freqs[i];
      n.lastAccess = i + 1;
      leastRecentFirst.add(n);
    }
    policy.rebuildFrom(leastRecentFirst);
    policy.checkInvariants();
    assertThat(policy.size()).isEqualTo(4);
    assertThat(policy.bucketFrequencies()).containsExactly(1L, 2L); // d's 0 floors to 1
    assertThat(drainVictims()).containsExactly("b", "d", "a", "c");
  }

  @Test
  void rebuildDiscardsPreviousState() {
    insert("x");
    policy.rebuildFrom(List.of());
    policy.checkInvariants();
    assertThat(policy.size()).isZero();
    assertThat(policy.pollVictim()).isNull();
  }

  @Test
  void checkInvariantsDetectsAFrequencyMismatch() {
    Node<String, String> a = insert("a");
    a.frequency = 7;
    assertThatThrownBy(policy::checkInvariants)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("frequency");
  }

  @Test
  void pollVictimOnEmptyPolicyReturnsNull() {
    assertThat(policy.pollVictim()).isNull();
  }
}
