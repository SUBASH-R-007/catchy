package io.cachelab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cachelab.testing.FakeTicker;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ApiTypesTest {

  @Test
  void policySnapshotCopiesItsEntries() {
    List<PolicySnapshot.Entry<String>> entries = new ArrayList<>();
    entries.add(new PolicySnapshot.Entry<>("a", 3));
    PolicySnapshot<String> snapshot = new PolicySnapshot<>(PolicyType.LFU, entries);
    entries.clear();
    assertThat(snapshot.entries()).containsExactly(new PolicySnapshot.Entry<>("a", 3));
    assertThatThrownBy(() -> snapshot.entries().add(new PolicySnapshot.Entry<>("b", 1)))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void policySnapshotRejectsNulls() {
    assertThatThrownBy(() -> new PolicySnapshot<String>(null, List.of()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new PolicySnapshot.Entry<String>(null, 1))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new PolicySnapshot.Entry<>("a", -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void entryViewValidates() {
    EntryView<String> view = new EntryView<>("k", 2, Optional.of(Duration.ofSeconds(1)));
    assertThat(view.ttlRemaining()).contains(Duration.ofSeconds(1));
    assertThatThrownBy(() -> new EntryView<>("k", 0, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new EntryView<>("k", -1, Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void systemTickerIsMonotonic() {
    Ticker ticker = Ticker.system();
    long first = ticker.read();
    long second = ticker.read();
    assertThat(second - first).isGreaterThanOrEqualTo(0);
    assertThat(Ticker.system()).isSameAs(ticker);
  }

  @Test
  void fakeTickerOnlyMovesForward() {
    FakeTicker ticker = new FakeTicker(-10);
    assertThat(ticker.read()).isEqualTo(-10);
    ticker.advanceMillis(1).advance(Duration.ofNanos(10));
    assertThat(ticker.read()).isEqualTo(1_000_000);
    assertThatThrownBy(() -> ticker.advance(Duration.ofNanos(-1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void cacheLoadExceptionPreservesCause() {
    IllegalStateException cause = new IllegalStateException("db down");
    CacheLoadException e = new CacheLoadException("load failed for key k", cause);
    assertThat(e).hasMessage("load failed for key k").hasCause(cause);
  }
}
