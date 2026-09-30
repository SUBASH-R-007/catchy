package io.cachelab.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExpiryIndexTest {

  private final ExpiryIndex<String, String> index = new ExpiryIndex<>();
  private final List<String> expired = new ArrayList<>();

  private Node<String, String> node(String key, long expiresAt) {
    Node<String, String> n = new Node<>(key, "v");
    n.expiresAt = expiresAt;
    n.version = 1;
    index.schedule(n);
    return n;
  }

  private int purge(long now, int max) {
    return index.purgeExpired(
        now,
        max,
        n -> {
          n.removed = true;
          expired.add(n.key);
        });
  }

  @Test
  void neverExpiringNodesAreNotScheduled() {
    node("forever", Node.NEVER);
    assertThat(index.size()).isZero();
  }

  @Test
  void purgesOnlyExpiredNodesInDeadlineOrder() {
    node("late", 300);
    node("early", 100);
    node("mid", 200);
    assertThat(purge(199, 10)).isEqualTo(1);
    assertThat(purge(250, 10)).isEqualTo(1);
    assertThat(expired).containsExactly("early", "mid");
    assertThat(index.size()).isEqualTo(1);
  }

  @Test
  void respectsTheMaximumPerCall() {
    for (int i = 0; i < 10; i++) {
      node("k" + i, i);
    }
    assertThat(purge(100, 4)).isEqualTo(4);
    assertThat(expired).containsExactly("k0", "k1", "k2", "k3");
  }

  @Test
  void aReplacedEntrysOldTicketIsStale() {
    Node<String, String> n = node("k", 100);
    // put again: new version and a later deadline; the old ticket must not expire it
    n.version++;
    n.expiresAt = 500;
    index.schedule(n);
    assertThat(purge(150, 10)).isZero();
    assertThat(expired).isEmpty();
    assertThat(index.size()).isEqualTo(1); // the stale ticket was discarded while polling
    assertThat(purge(500, 10)).isEqualTo(1);
    assertThat(expired).containsExactly("k");
  }

  @Test
  void aReplacementWithoutTtlCancelsTheOldDeadline() {
    Node<String, String> n = node("k", 100);
    n.version++;
    n.expiresAt = Node.NEVER;
    index.schedule(n); // not scheduled: never expires
    assertThat(purge(1_000, 10)).isZero();
  }

  @Test
  void removedNodesAreSkipped() {
    Node<String, String> n = node("gone", 100);
    n.removed = true;
    node("live", 100);
    assertThat(purge(100, 10)).isEqualTo(1);
    assertThat(expired).containsExactly("live");
  }

  @Test
  void deadlinesCompareCorrectlyAcrossTickerWrapAround() {
    long nearMax = Long.MAX_VALUE - 50;
    node("before-wrap", nearMax + 10);
    node("after-wrap", nearMax + 100); // overflows to a negative number
    assertThat(purge(nearMax + 20, 10)).isEqualTo(1);
    assertThat(expired).containsExactly("before-wrap");
    assertThat(purge(nearMax + 100, 10)).isEqualTo(1);
    assertThat(expired).containsExactly("before-wrap", "after-wrap");
  }

  @Test
  void compactionKeepsOnlyLiveScheduledNodes() {
    List<Node<String, String>> live = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      Node<String, String> n = node("k" + i, 1_000 + i);
      for (int v = 0; v < 3; v++) { // three stale tickets per node
        n.version++;
        index.schedule(n);
      }
      live.add(n);
    }
    assertThat(index.size()).isEqualTo(20);
    assertThat(index.needsCompaction(5)).isFalse(); // 20 <= 2 * 5 + 1024
    index.compact(live);
    assertThat(index.size()).isEqualTo(5);
    assertThat(purge(2_000, 10)).isEqualTo(5);
  }

  @Test
  void needsCompactionFollowsTheSpecThreshold() {
    for (int i = 0; i < 1_025; i++) {
      node("k" + i, 10);
    }
    assertThat(index.needsCompaction(0)).isTrue();
    assertThat(index.needsCompaction(1)).isFalse();
  }
}
