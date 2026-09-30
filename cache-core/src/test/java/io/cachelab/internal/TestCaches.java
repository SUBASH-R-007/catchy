package io.cachelab.internal;

import io.cachelab.PolicyType;
import io.cachelab.RemovalCause;
import io.cachelab.Ticker;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Builds engine instances for tests: no sweeper thread, a recording removal listener. */
final class TestCaches {

  private TestCaches() {}

  /** A removal seen by the listener. */
  record Event(String key, String value, RemovalCause cause) {}

  /** A cache plus everything its removal listener saw. */
  record Recorded(BoundedCache<String, String> cache, List<Event> events) {
    List<String> keys(RemovalCause cause) {
      synchronized (events) {
        return events.stream().filter(e -> e.cause() == cause).map(Event::key).toList();
      }
    }
  }

  static Recorded recorded(int capacity, PolicyType policy, Ticker ticker, long defaultTtlNanos) {
    List<Event> events = Collections.synchronizedList(new ArrayList<>());
    CacheSettings<String, String> settings =
        new CacheSettings<>(
            "test",
            capacity,
            policy,
            defaultTtlNanos,
            1,
            (String k, String v, RemovalCause c) -> events.add(new Event(k, v, c)),
            null,
            List.of(),
            ticker,
            TimeUnit.MILLISECONDS.toNanos(100),
            TimeUnit.SECONDS.toNanos(10));
    return new Recorded(new BoundedCache<>(settings, new AtomicLong()), events);
  }

  static Recorded recorded(int capacity, PolicyType policy, Ticker ticker) {
    return recorded(capacity, policy, ticker, CacheSettings.NO_TTL);
  }
}
