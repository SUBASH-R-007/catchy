package io.cachelab.server.cache;

import static org.assertj.core.api.Assertions.assertThat;

import io.cachelab.RemovalCause;
import io.cachelab.server.metrics.RemovalEvent;
import java.util.List;
import org.junit.jupiter.api.Test;

class EventRingTest {

  private static RemovalEvent event(int i) {
    return new RemovalEvent(i, "c", "k" + i, RemovalCause.EVICTED);
  }

  private static List<String> keys(List<RemovalEvent> events) {
    return events.stream().map(RemovalEvent::key).toList();
  }

  @Test
  void returnsEventsSinceASequenceNumber() {
    EventRing ring = new EventRing();
    ring.add(event(0));
    long mark = ring.nextSequence();
    ring.add(event(1));
    ring.add(event(2));
    assertThat(keys(ring.since(mark, 50))).containsExactly("k1", "k2");
    assertThat(ring.since(ring.nextSequence(), 50)).isEmpty();
  }

  @Test
  void keepsOnlyTheNewestTwoHundred() {
    EventRing ring = new EventRing();
    for (int i = 0; i < 450; i++) {
      ring.add(event(i));
    }
    List<RemovalEvent> all = ring.latest(1_000);
    assertThat(all).hasSize(EventRing.CAPACITY);
    assertThat(all.get(0).key()).isEqualTo("k250");
    assertThat(all.get(199).key()).isEqualTo("k449");
    assertThat(keys(ring.since(0, 1_000))).hasSize(200); // overwritten events are skipped
  }

  @Test
  void capsTheResultToTheNewestMax() {
    EventRing ring = new EventRing();
    for (int i = 0; i < 80; i++) {
      ring.add(event(i));
    }
    assertThat(keys(ring.since(0, 50))).hasSize(50).startsWith("k30").endsWith("k79");
    assertThat(ring.latest(0)).isEmpty();
  }
}
