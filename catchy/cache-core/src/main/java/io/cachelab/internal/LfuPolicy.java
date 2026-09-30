package io.cachelab.internal;

import io.cachelab.PolicySnapshot;
import io.cachelab.PolicyType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Least-frequently-used eviction in O(1), after Shah, Mitra and Matani: a doubly linked list of
 * {@link FreqBucket}s in strictly ascending frequency, each holding its nodes most-recent-first.
 * The victim is the least recent node of the first (lowest-frequency) bucket, so ties are broken by
 * recency. Never reads time or TTL.
 *
 * <p>There is deliberately no {@code minFreq} counter: it goes stale when TTL removes the last node
 * at the minimum frequency. The first bucket after the head sentinel is always the minimum.
 *
 * <p>Thread-safety: not thread-safe; guarded by the owning cache's lock. Complexity: O(1) per
 * operation; {@link #snapshot(int)} O(limit + buckets visited); {@link #rebuildFrom(List)} O(n log
 * b) for b distinct frequencies; {@link #checkInvariants()} O(n).
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public class LfuPolicy<K, V> implements EvictionPolicy<K, V> {

  /** Sentinel before the lowest bucket. */
  final FreqBucket<K, V> head = new FreqBucket<>(0);

  /** Sentinel after the highest bucket. */
  final FreqBucket<K, V> tail = new FreqBucket<>(Long.MAX_VALUE);

  int size;

  /** Creates an empty policy. */
  public LfuPolicy() {
    head.next = tail;
    tail.prev = head;
  }

  @Override
  public void onInsert(Node<K, V> n) {
    n.frequency = 1;
    FreqBucket<K, V> first = head.next;
    FreqBucket<K, V> target = first.freq == 1 ? first : linkBucketAfter(head, 1);
    target.entries.linkFirst(n);
    n.bucket = target;
    size++;
  }

  @Override
  public void onAccess(Node<K, V> n) {
    FreqBucket<K, V> current = n.bucket;
    long nextFreq = n.frequency + 1;
    FreqBucket<K, V> next = current.next;
    FreqBucket<K, V> target = next.freq == nextFreq ? next : linkBucketAfter(current, nextFreq);
    current.entries.unlink(n);
    target.entries.linkFirst(n);
    n.bucket = target;
    n.frequency = nextFreq;
    if (current.entries.isEmpty()) {
      unlinkBucket(current);
    }
  }

  @Override
  public void onRemove(Node<K, V> n) {
    FreqBucket<K, V> bucket = n.bucket;
    bucket.entries.unlink(n);
    n.bucket = null;
    size--;
    if (bucket.entries.isEmpty()) {
      unlinkBucket(bucket);
    }
  }

  @Override
  public Node<K, V> pollVictim() {
    FreqBucket<K, V> first = head.next;
    if (first == tail) {
      return null;
    }
    Node<K, V> victim = first.entries.pollLast();
    victim.bucket = null;
    size--;
    if (first.entries.isEmpty()) {
      unlinkBucket(first);
    }
    return victim;
  }

  @Override
  public PolicySnapshot<K> snapshot(int limit) {
    List<PolicySnapshot.Entry<K>> entries = new ArrayList<>(Math.min(limit, size));
    for (FreqBucket<K, V> b = tail.prev; b != head && entries.size() < limit; b = b.prev) {
      for (Node<K, V> n : b.entries) {
        if (entries.size() >= limit) {
          break;
        }
        entries.add(new PolicySnapshot.Entry<>(n.key, n.frequency));
      }
    }
    return new PolicySnapshot<>(type(), entries);
  }

  @Override
  public void rebuildFrom(List<Node<K, V>> nodesLeastRecentFirst) {
    clearBuckets();
    Map<Long, FreqBucket<K, V>> byFreq = new TreeMap<>();
    for (Node<K, V> n : nodesLeastRecentFirst) {
      long f = Math.max(1, n.frequency);
      FreqBucket<K, V> bucket = byFreq.computeIfAbsent(f, FreqBucket::new);
      n.prev = null;
      n.next = null;
      n.frequency = f;
      n.bucket = bucket;
      bucket.entries.linkFirst(n); // later (more recent) nodes end up in front
    }
    for (FreqBucket<K, V> bucket : byFreq.values()) {
      linkBucketBefore(tail, bucket);
    }
    size = nodesLeastRecentFirst.size();
  }

  @Override
  public void checkInvariants() {
    int count = 0;
    long previousFreq = head.freq;
    FreqBucket<K, V> prev = head;
    for (FreqBucket<K, V> b = head.next; b != tail; b = b.next) {
      if (b == null || b.prev != prev) {
        throw new IllegalStateException("LFU bucket links are asymmetric after " + prev);
      }
      if (b.freq <= previousFreq) {
        throw new IllegalStateException(
            "LFU buckets not strictly ascending: " + previousFreq + " then " + b.freq);
      }
      if (b.entries.isEmpty()) {
        throw new IllegalStateException("LFU bucket " + b.freq + " is empty");
      }
      count += b.entries.checkInvariants("LFU bucket " + b.freq);
      for (Node<K, V> n : b.entries) {
        if (n.bucket != b || n.frequency != b.freq) {
          throw new IllegalStateException(
              "node " + n + " has frequency " + n.frequency + " but sits in bucket " + b.freq);
        }
      }
      if (count > size) {
        throw new IllegalStateException("LFU holds more nodes than its size " + size);
      }
      previousFreq = b.freq;
      prev = b;
    }
    if (tail.prev != prev) {
      throw new IllegalStateException("LFU tail sentinel does not point at the last bucket");
    }
    if (count != size) {
      throw new IllegalStateException("LFU walked " + count + " nodes but size is " + size);
    }
  }

  @Override
  public PolicyType type() {
    return PolicyType.LFU;
  }

  @Override
  public int size() {
    return size;
  }

  /** The bucket frequencies in ascending order, for tests and diagnostics. O(b). */
  List<Long> bucketFrequencies() {
    List<Long> freqs = new ArrayList<>();
    for (FreqBucket<K, V> b = head.next; b != tail; b = b.next) {
      freqs.add(b.freq);
    }
    return freqs;
  }

  FreqBucket<K, V> linkBucketAfter(FreqBucket<K, V> anchor, long freq) {
    FreqBucket<K, V> bucket = new FreqBucket<>(freq);
    linkBucketBefore(anchor.next, bucket);
    return bucket;
  }

  void linkBucketBefore(FreqBucket<K, V> anchor, FreqBucket<K, V> bucket) {
    FreqBucket<K, V> before = anchor.prev;
    bucket.prev = before;
    bucket.next = anchor;
    before.next = bucket;
    anchor.prev = bucket;
  }

  void unlinkBucket(FreqBucket<K, V> bucket) {
    bucket.prev.next = bucket.next;
    bucket.next.prev = bucket.prev;
    bucket.prev = null;
    bucket.next = null;
  }

  void clearBuckets() {
    for (FreqBucket<K, V> b = head.next; b != tail; b = b.next) {
      b.entries.clear();
    }
    head.next = tail;
    tail.prev = head;
    size = 0;
  }
}
