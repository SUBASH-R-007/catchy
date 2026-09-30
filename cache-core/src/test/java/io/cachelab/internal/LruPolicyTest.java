package io.cachelab.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.cachelab.PolicySnapshot;
import io.cachelab.PolicyType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LruPolicyTest {

  private final LruPolicy<String, String> policy = new LruPolicy<>();

  private Node<String, String> insert(String key) {
    Node<String, String> n = new Node<>(key, "v");
    policy.onInsert(n);
    policy.checkInvariants();
    return n;
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
  void evictsLeastRecentlyUsedFirst() {
    insert("a");
    Node<String, String> b = insert("b");
    insert("c");
    policy.onAccess(b);
    assertThat(drainVictims()).containsExactly("a", "c", "b");
    assertThat(policy.type()).isEqualTo(PolicyType.LRU);
  }

  @Test
  void removedEntriesAreNeverVictims() {
    Node<String, String> a = insert("a");
    insert("b");
    policy.onRemove(a);
    policy.checkInvariants();
    assertThat(drainVictims()).containsExactly("b");
  }

  @Test
  void snapshotIsMostRecentFirstWithFrequencyZero() {
    Node<String, String> a = insert("a");
    insert("b");
    policy.onAccess(a);
    assertThat(policy.snapshot(10))
        .isEqualTo(
            new PolicySnapshot<>(
                PolicyType.LRU,
                List.of(new PolicySnapshot.Entry<>("a", 0), new PolicySnapshot.Entry<>("b", 0))));
    assertThat(policy.snapshot(1).entries())
        .extracting(PolicySnapshot.Entry::key)
        .containsExactly("a");
  }

  @Test
  void rebuildPutsTheMostRecentFirst() {
    List<Node<String, String>> leastRecentFirst =
        List.of(new Node<>("old", "v"), new Node<>("mid", "v"), new Node<>("new", "v"));
    leastRecentFirst.get(0).frequency = 9; // LRU ignores frequency
    policy.rebuildFrom(leastRecentFirst);
    policy.checkInvariants();
    assertThat(policy.size()).isEqualTo(3);
    assertThat(drainVictims()).containsExactly("old", "mid", "new");
  }

  @Test
  void emptyPolicyHasNoVictim() {
    assertThat(policy.pollVictim()).isNull();
    assertThat(policy.snapshot(5).entries()).isEmpty();
  }
}
