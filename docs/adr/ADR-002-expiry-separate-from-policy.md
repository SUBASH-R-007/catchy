# ADR-002 — Expiry is separate from the eviction policy

- **Status:** Accepted (Step 1). Implemented in Step 2.
- **Context:** The cache needs per-entry TTL, and TTL must behave the same under every eviction
  strategy (SPEC 1: "TTL independent of eviction strategy").

## Decision

Eviction and expiry are two components that never read each other's state:

| Component | Owns | Knows about time? |
|---|---|---|
| `EvictionPolicy` (LRU, LFU, LFU_DECAY) | recency/frequency order, victim choice | **No.** Policies never read the ticker, TTL or `expiresAt`. Code review rejects a `Ticker` import in a policy class. |
| `ExpiryIndex` + `Sweeper` | expiry deadlines, purging | Yes |
| Engine (`BoundedCache`) | the map, the lock, coordination | Reads the ticker once per call, *before* taking the lock |

- **Lazy expiry:** `get` checks `node.isExpired(now)` before touching the policy. An expired hit
  becomes a miss plus an expiration, and the entry is removed.
- **Active expiry:** `ExpiryIndex` is a `PriorityQueue<Ticket(expiresAt, version, node)>`. A
  background sweeper (one daemon thread per cache) purges up to 256 expired entries per
  interval.
- **Versioned tickets:** every `put` increments `node.version` and schedules a new ticket. Old
  tickets become *stale* (`node.removed || ticket.version != node.version`) and are discarded when
  polled. This avoids O(n) heap removal on every TTL reset.
- **Compaction:** when the queue exceeds `2 × size + 1024` tickets, it is rebuilt from live nodes.
- **Purge before evict:** inserting a new key into a full cache first purges up to 16 expired
  entries, so live entries are not evicted while dead ones occupy space.
- The policy is only ever told "this node was removed" (`onRemove`).
- The `LFU_DECAY` schedule is a time decision, so the engine owns it. The sweeper calls
  `policy.decay()` when `decayInterval` has elapsed; the policy stays time-free.

## Consequences

- **TTL behaviour is policy-independent by construction.** Step 2 has parameterized tests run the
  same TTL scenarios against LRU and LFU with a `FakeTicker`, with no sleeps.
- `size()` may briefly include expired-but-unswept entries, for at most one sweep interval. This
  is documented on `Cache#size()`.
- **Cost:** O(log n) per scheduled TTL (heap insert), plus stale tickets that are bounded by
  compaction.
- **Overflow safety:** `isExpired` compares with subtraction (`now - expiresAt >= 0`) so ticker
  wrap-around is harmless. The "never" sentinel (`Long.MAX_VALUE`) is checked explicitly, because
  ticker readings may be negative.

## Future work: timer wheel

A hierarchical timer wheel (as in Kafka or Caffeine) makes scheduling and expiring O(1) amortised
by bucketing deadlines into coarse slots. It trades exact ordering for bucket granularity and adds
complexity (cascading between wheels). The heap is simpler, exact, and fast enough at demo scale
(≤ 10⁵ entries). A wheel is the upgrade path if TTL churn dominates profiles.
