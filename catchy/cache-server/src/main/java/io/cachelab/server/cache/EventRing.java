package io.cachelab.server.cache;

import io.cachelab.server.metrics.RemovalEvent;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The last {@value #CAPACITY} removal events across all caches, fed by the caches' removal
 * listeners (SPEC 8.1). Each event gets a sequence number so the metrics publisher can take "the
 * events since the last tick" without losing or repeating any that are still in the ring.
 *
 * <p>Thread-safe (short synchronized sections). {@link #add} is O(1); reads are O(result).
 */
@Component
public class EventRing {

  /** Number of events kept. */
  public static final int CAPACITY = 200;

  private final RemovalEvent[] ring = new RemovalEvent[CAPACITY];
  private long nextSequence; // sequence number of the next event added

  /**
   * Records one removal.
   *
   * @param event the event
   */
  public synchronized void add(RemovalEvent event) {
    ring[(int) (nextSequence % CAPACITY)] = event;
    nextSequence++;
  }

  /**
   * Returns the sequence number the next event will get; pass it to {@link #since(long, int)} later
   * to read only newer events.
   *
   * @return the next sequence number
   */
  public synchronized long nextSequence() {
    return nextSequence;
  }

  /**
   * Returns events with a sequence number of at least {@code fromSequence}, oldest first, keeping
   * only the newest {@code max} if there are more. Events already overwritten are skipped.
   *
   * @param fromSequence the first sequence number wanted
   * @param max the maximum number of events to return
   * @return the events, oldest first
   */
  public synchronized List<RemovalEvent> since(long fromSequence, int max) {
    long oldestKept = Math.max(0, nextSequence - CAPACITY);
    long from = Math.max(Math.max(fromSequence, oldestKept), nextSequence - max);
    List<RemovalEvent> events = new ArrayList<>((int) Math.max(0, nextSequence - from));
    for (long s = from; s < nextSequence; s++) {
      events.add(ring[(int) (s % CAPACITY)]);
    }
    return events;
  }

  /**
   * Atomically returns the events since a cursor (as {@link #since(long, int)}) together with the
   * cursor to pass next time, so no event is lost or repeated between two reads.
   *
   * @param cursor the first sequence number wanted (a previous {@link Batch#nextCursor()})
   * @param max the maximum number of events to return; the newest are kept
   * @return the events, oldest first, and the next cursor
   */
  public synchronized Batch read(long cursor, int max) {
    return new Batch(since(cursor, max), nextSequence);
  }

  /**
   * Result of {@link #read(long, int)}.
   *
   * @param events the events, oldest first; never {@code null}
   * @param nextCursor the sequence number of the next event to be added
   */
  public record Batch(List<RemovalEvent> events, long nextCursor) {}

  /**
   * Returns the newest {@code max} events, oldest first.
   *
   * @param max the maximum number of events
   * @return the events
   */
  public List<RemovalEvent> latest(int max) {
    return since(0, max);
  }
}
