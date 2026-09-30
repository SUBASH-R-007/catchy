# ADR-003 — LFU with frequency buckets instead of `minFreq`

- **Status:** Accepted (Step 2)
- **Context:** LFU must evict the least frequently used entry in O(1), with ties broken by
  recency. TTL can remove any entry at any time (ADR-002).

## Options

1. **Heap keyed by (frequency, recency).** O(log n) per access, because every hit re-heapifies.
   Rejected: the spec requires O(1).
2. **`HashMap<freq, LinkedHashSet>` plus a `minFreq` integer** (the classic interview answer).
   It is O(1) until TTL removes the last entry at `minFreq`. `minFreq` then points at an empty
   frequency, and finding the next one needs a scan, or it silently evicts from the wrong place.
   The bug only shows up when expiry and eviction interact — exactly our product.
3. **Frequency buckets in a doubly linked list** (Shah, Mitra and Matani, 2010). **Chosen.**

## Decision

- A doubly linked list of `FreqBucket`s with head and tail sentinels, kept in strictly ascending
  frequency. Each bucket holds an intrusive list of its nodes, most recent first. Each node
  points back at its bucket.
- **Hit:** move the node to the bucket `f + 1`. That bucket is either `bucket.next` or a new one
  linked right after the current bucket. If the old bucket is now empty, delete it.
- **Victim:** the back (least recent) node of `head.next` — the first bucket is *by construction*
  the minimum frequency.
- **Removal for any reason** (explicit, TTL, replace): unlink the node, and delete its bucket if
  it is empty. Nothing else can go stale.
- `rebuildFrom` (runtime policy switch) groups nodes with a temporary `TreeMap`, in O(n log b)
  for b distinct frequencies.

## Consequences

- Every operation is O(1) with no amortisation, and no allocation beyond creating a bucket.
- **Invariants are checkable:**
  - buckets are strictly ascending and non-empty;
  - each node's `frequency` equals its `bucket.freq`;
  - links are symmetric;
  - the count equals the size.

  `checkInvariants()` runs after every operation in the differential tests: 10,000 sequences ×
  200 operations, against a linear-scan reference model.
- **Regression test:** `LfuPolicyTest.removingTheLastMinimumFrequencyNodeKeepsEvictionCorrect`,
  plus an engine-level TTL test (`ttlRemovalOfTheLastMinimumFrequencyNodeDoesNotBreakEviction`).
- **Memory:** two extra references (`bucket`, plus list links shared with LRU) and a `long`
  frequency per node, plus one small object per distinct frequency.
