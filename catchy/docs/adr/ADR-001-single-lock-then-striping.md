# ADR-001 — Single lock first, lock striping second

- **Status:** Accepted (Step 1). The segmented cache is built in Step 3.
- **Context:** The cache must support concurrent `get`/`put` safely (SPEC 1). Both LRU and LFU
  mutate shared ordering structures on *every* access, including reads.

## Decision

1. **`BoundedCache` uses one non-fair `ReentrantLock`** around a `HashMap` plus an intrusive
   eviction structure. Every operation that touches ordering takes the lock.
2. **`SegmentedCache` stripes the key space** over `N` (power of two) independent
   `BoundedCache` segments, selected by `spread(hash) & (N - 1)` with
   `spread(h) = h ^ (h >>> 16)`. Capacity is split exactly: the first `maximumSize % N` segments
   get one extra slot, so the total equals `maximumSize`.
3. `concurrencyLevel(1)` (the default) builds the single-lock cache; `> 1` builds the segmented
   one. The builder rejects a level larger than the maximum size.

## Why not a read-write lock?

A `get` is a write: LRU moves the node to the front, LFU moves it to the next frequency bucket.
A read lock would permit concurrent structural mutation. A `ReadWriteLock` would therefore
degrade to exclusive locking with extra overhead.

## Consequences

- **Correctness is simple to argue.** One lock gives exact global LRU/LFU order, and the
  invariant checks (`checkInvariants()`) observe a consistent structure.
- **Throughput under contention is limited** by one lock; striping recovers parallelism.
- **Trade-off of striping:** ordering is exact *within* a segment and approximate *globally*. The
  victim is the least-recent/least-frequent entry of the segment that receives the new key, not
  of the whole cache. For LRU snapshots, a shared `AtomicLong` access tick lets
  `policySnapshot` merge segments by recency; LFU snapshots merge by frequency.
- User code (listeners, observers, loaders) never runs under the lock (SPEC 4.6), so a slow
  listener cannot stall other threads' cache operations.
- JMH benchmarks (Step 4) quantify the single vs segmented trade-off against a synchronized
  `LinkedHashMap` baseline and Caffeine.
