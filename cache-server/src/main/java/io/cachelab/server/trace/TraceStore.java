package io.cachelab.server.trace;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Keeps uploaded traces in memory (SPEC 9.5): at most {@value #MAX_TRACES}; storing another evicts
 * the oldest. Ids are {@code t-<n>}, never reused.
 *
 * <p>Thread-safe (synchronized; every operation is O(1)).
 */
@Component
public class TraceStore {

  /** Maximum number of traces kept. */
  public static final int MAX_TRACES = 3;

  private final AtomicLong lastId = new AtomicLong();
  private final Map<String, List<String>> traces = new LinkedHashMap<>();

  /**
   * Stores a trace, evicting the oldest one if {@value #MAX_TRACES} are already kept.
   *
   * @param keys the keys in order; stored as an unmodifiable copy
   * @return the new trace's id
   */
  public synchronized String put(List<String> keys) {
    List<String> copy = List.copyOf(Objects.requireNonNull(keys, "keys"));
    String id = "t-" + lastId.incrementAndGet();
    traces.put(id, copy);
    while (traces.size() > MAX_TRACES) {
      traces.remove(traces.keySet().iterator().next());
    }
    return id;
  }

  /**
   * Returns a stored trace.
   *
   * @param id the trace id
   * @return the keys in order
   * @throws TraceNotFoundException if no trace with that id is kept
   */
  public synchronized List<String> get(String id) {
    List<String> keys = traces.get(id);
    if (keys == null) {
      throw new TraceNotFoundException(id);
    }
    return keys;
  }

  /**
   * Returns the ids of the kept traces, oldest first.
   *
   * @return the ids
   */
  public synchronized List<String> ids() {
    return List.copyOf(traces.keySet());
  }
}
