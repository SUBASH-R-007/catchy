package io.cachelab.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class NodeTest {

  @Test
  void newNodeNeverExpires() {
    Node<String, String> n = new Node<>("k", "v");
    assertThat(n.expiresAt).isEqualTo(Node.NEVER);
    assertThat(n.isExpired(0)).isFalse();
    assertThat(n.isExpired(Long.MAX_VALUE)).isFalse();
    // System.nanoTime() may be negative; "never" must still mean never.
    assertThat(n.isExpired(Long.MIN_VALUE)).isFalse();
    assertThat(n.isExpired(-5)).isFalse();
  }

  @Test
  void expiresExactlyAtTheDeadline() {
    Node<String, String> n = new Node<>("k", "v");
    n.expiresAt = 1_000;
    assertThat(n.isExpired(999)).isFalse();
    assertThat(n.isExpired(1_000)).isTrue();
    assertThat(n.isExpired(1_001)).isTrue();
  }

  @Test
  void comparisonSurvivesTickerWrapAround() {
    Node<String, String> n = new Node<>("k", "v");
    long nearMax = Long.MAX_VALUE - 10;
    n.expiresAt = nearMax + 20; // wraps to a negative number
    assertThat(n.expiresAt).isNegative();
    assertThat(n.isExpired(nearMax)).isFalse();
    assertThat(n.isExpired(nearMax + 19)).isFalse();
    assertThat(n.isExpired(nearMax + 20)).isTrue();
  }

  @Test
  void rejectsNullKey() {
    assertThatThrownBy(() -> new Node<>(null, "v")).isInstanceOf(NullPointerException.class);
  }
}
