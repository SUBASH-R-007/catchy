# ADR-004 — LFU decay by halving, with a linear merge

- **Status:** Accepted (Step 3)
- **Context:** Plain LFU clings to formerly popular keys. When the hot set shifts
  (SHIFTING_HOTSPOT, the morning surge in FORMULARY), old favourites keep high counts and new hot
  keys are evicted before they can build frequency. We need ageing that keeps O(1) operations.

## Decision

- **Policy:** `LfuDecayPolicy` extends the bucket LFU (ADR-003). `decay()` maps every frequency
  `f → max(1, f >> 1)`.
- **Scheduling:** the engine calls `decay()` from the sweeper, under the cache lock, once
  `decayInterval` (default 10 s) has elapsed. This is a time decision, so the engine owns it; the
  policy never reads time (ADR-002).
- **Linear merge:** halving preserves the order between buckets, so the source buckets that
  collapse onto one new frequency are *adjacent*:
  - 1, 2 and 3 all become 1;
  - `2k` and `2k+1` become `k`.

  One ascending walk visits each source bucket once. Each source list is already
  most-recent-first, so the lists merge by `lastAccess` like merge sort's merge step — in place,
  by relinking nodes. Equal frequencies therefore still evict least-recent-first.

## Measurements

`LfuDecayPolicyTest.measuresTheDecayPauseAt10kAnd100kEntries` builds a skewed population and times
one `decay()` after JIT warm-up. Measured on the development machine (JDK 21, Windows 11):

| Entries | Distinct frequencies | Pause |
|---:|---:|---:|
| 10,000 | 48 | **0.72 ms** |
| 100,000 | 53 | **3.99 ms** |

The pause is O(n) and runs once per decay interval, while the lock is held. At the default 10 s
interval, a 100k-entry cache spends about 0.04 % of wall time decaying.

## Consequences

- **Correctness:** after any number of decays, eviction order equals a reference LFU with the same
  halving applied. This is checked on 2,000 random sequences, with `checkInvariants()` after
  every decay.
- **Cost:** `get`/`put` stay O(1); only the decay step is O(n).
- **Latency:** for very large caches (≥ 10⁶ entries), a decay pause of about 40 ms would be
  visible in tail latency. The upgrade path is either decaying segment by segment (each segment
  is a smaller linear pass) or lazy per-node ageing using an epoch counter.
- **Proof in the product:** the policy advisor (Step 4) compares LFU_DECAY against LRU and LFU
  shadows on the live traffic, so the benefit is shown rather than assumed.
