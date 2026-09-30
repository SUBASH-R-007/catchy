# CacheLab — Master Build Spec for Claude Code

> **For the team (not for Claude):**
> 1. Create an empty folder, run `git init`, and save this file in it as `SPEC.md`.
> 2. Start Claude Code (model: Opus 5.5, effort: medium) in that folder and paste the kickoff prompt:
>    `Read SPEC.md completely before doing anything. Follow section 0 exactly. Begin with Step 1 and stop at its gate.`
> 3. At each gate, review the report and the running app, then reply `continue with Step N`.
> 4. When a session gets long, start a fresh one: `Read SPEC.md, CLAUDE.md and PROGRESS.md, then continue from the next action in PROGRESS.md.`
> 5. To run every step without pausing, say `run all remaining steps` (Claude still verifies each gate).

---

## 0. Operating rules for Claude Code

1. **Read this entire file before writing code.** It is the single source of truth. Section numbers are referenced throughout.
2. **Work in the five steps of section 12, in order.** Within a step, build modules in the listed order. At each gate:
   1. run every verification command listed for that gate;
   2. update `PROGRESS.md`;
   3. commit;
   4. **stop and report**: what was built, how to run it, test results, and any deviations.
   Wait for the user to say `continue` — unless they said `run all remaining steps`, in which case continue automatically **only if the gate passed**.
3. **In Step 1, create `CLAUDE.md`** at the repo root. It holds a condensed copy of section 0 and section 2 (conventions), so future sessions inherit the rules.
4. **Maintain `PROGRESS.md`:** current step, a checklist of modules and parts done, known issues, and the exact next action. Update it with every commit. It is how a fresh session resumes work.
5. **Record decisions.** Any decision this spec does not fix goes into `docs/DECISIONS.md` as one line: the decision and the reason.
6. **When the spec is impossible or contradictory,** choose the simplest option that best serves the judging criteria (section 1). Record it in `DECISIONS.md` and mention it in the gate report. **Never silently drop a feature.**
7. **Do not over-engineer.** Build what is listed. Add no frameworks, libraries or features beyond this spec unless required to meet it.
8. **Verify before claiming done.**
   - Run the tests and builds.
   - For UI work: run the dev server and check it in a browser if a browser tool is available. Otherwise, at minimum, build, typecheck and run the component tests.
   - A feature is done only when its "done when" checks pass.
9. **Commit after each module (or module part)** using conventional commits, e.g. `feat(core): O(1) LFU policy with frequency buckets`.
10. **`cache-core` must have zero runtime dependencies.** Always.
11. **Slow down and write tests first** at these tricky points:
    - LFU bucket list (4.3)
    - locking and listener dispatch (4.6)
    - expiry index versioning (4.5)
    - LFU-decay merge (6.1)
    - runtime policy switch (6.2)
    - Bélády replay (6.4)
    - SSE emitter lifecycle (8.4)
12. **Subagents are optional.** Use them only for independent, well-scoped tasks (e.g. one dashboard page while backend tests run). You stay responsible for integrating and verifying their work.
13. **If files already exist** (the team may have completed Step 1 earlier), audit them against this spec. Fill the gaps and extend the interfaces **additively**. Do not recreate from scratch.

---

## 1. Product summary and judging criteria

**Hackathon:** Acentra Health (HackForge), Problem 2 — *Java Problem Statement: Custom Cache Library with Live Metrics Panel.*

**Minimum requirements**, each mapped to the section that meets it:

| Requirement | Section |
|---|---|
| In-memory cache | 4 |
| LRU eviction strategy | 4.3 |
| LFU eviction strategy | 4.3 |
| Selectable eviction policy | 4.1, 6.2 |
| Per-entry TTL | 4.5 |
| TTL independent of eviction strategy | 4.5, ADR-002 |
| Thread-safe concurrent GET/PUT | 4.6, 5 |
| Frontend metrics panel | 10 |
| Display cache hit rate and miss rate | 8.3, 10 |
| Show metrics against a sample access pattern | 9 |

**Judging criteria and how we win each:**

| Criterion | Our evidence |
|---|---|
| **Technical merit** | O(1) LRU and LFU; TTL fully separate from eviction; thread safety proven live with invariant checks; differential and stress tests; JMH benchmarks against baselines and Caffeine; clean module boundaries; ADRs |
| **Impact and usefulness** | Spring Boot starter (`@Cacheable` support); published jar; healthcare scenarios (drug formulary, provider directory); database calls and latency saved in plain numbers; a policy advisor that recommends the best policy; replay of real access logs |
| **Design and UX** | Developer UX (fluent builder, clear errors, Javadoc); a circuit-board themed dashboard with a guided demo mode, plain-language explanations, a live "Data bus" visualization, colour-blind-safe palette, accessibility and reduced-motion support |

**One-line pitch:** *"A cache you can trust under load: provably correct eviction, independent expiry, and a live panel that shows why LRU or LFU wins on your traffic."*

**Product name:** CacheLab. Group `io.cachelab`. Version `0.1.0`.

---

## 2. Tech stack and conventions

### Backend
- Java 21 (Temurin)
- Gradle 8.x, Kotlin DSL, version catalog `gradle/libs.versions.toml`
- Spring Boot 3.x (latest stable)
- springdoc-openapi, Micrometer
- JUnit 5 and AssertJ
- JMH via the `me.champeau.jmh` plugin
- Caffeine **only** in `cache-bench` and in test scope
- Spotless (google-java-format); JaCoCo on `cache-core`
- No Lombok

### Frontend
- Vite, React 18, TypeScript (strict)
- Tailwind CSS (colours mapped to CSS variables)
- React Router, Recharts, lucide-react
- `@fontsource` packages for Space Grotesk, Inter and JetBrains Mono. **No external font or CDN requests.**
- Vitest and Testing Library
- ESLint and Prettier

### Packages
| Package | Contents |
|---|---|
| `io.cachelab` | Public API: `Cache`, `CacheBuilder`, `CacheStats`, `PolicyType`, `RemovalCause`, `RemovalListener`, `AccessObserver`, `PolicySnapshot`, `EntryView`, `Ticker`, `CacheLoadException` |
| `io.cachelab.internal` | `Node`, `IntrusiveList`, `EvictionPolicy`, policies, `BoundedCache`, `SegmentedCache`, `ExpiryIndex`, `Sweeper`, `StatsRecorder` |
| `io.cachelab.advisor` | `ShadowCache`, `PolicyAdvisor`, `Recommendation`, `OptimalReplay`, `KeyRecorder` (public) |
| `io.cachelab.diagnostics` | `StressHarness`, `StressConfig`, `StressReport`, `InvariantChecker`, `StampedeTest` (public, main source set: the server uses them at runtime) |
| test fixtures | `FakeTicker`, reference models |

### Conventions
- Javadoc on every public type and method, stating:
  - semantics
  - null handling
  - thread-safety
  - complexity
- Conventional commits.
- A method longer than about 40 lines needs a reason.
- All randomness is seeded (`SplittableRandom`) unless it is explicitly for stress tests.

---

## 3. Repository layout (final)

```
cachelab/
├── SPEC.md  CLAUDE.md  PROGRESS.md  README.md  LICENSE (MIT)
├── settings.gradle.kts  build.gradle.kts  gradle/libs.versions.toml
├── cache-core/            # the library (zero runtime deps) + testFixtures
├── cache-bench/           # JMH benchmarks + result exporter
├── cache-spring/          # Spring Boot starter + Micrometer binder
├── cache-server/          # Spring Boot demo server (API, SSE, workloads, demo acts)
├── examples/formulary-service/   # tiny @Cacheable example app
├── dashboard/             # React app
├── samples/               # sample trace CSVs
├── scripts/               # build-all.sh, export helpers
├── docs/
│   ├── adr/  (ADR-001 … ADR-005)
│   ├── api/  (openapi.yaml, metrics.schema.json)
│   ├── DECISIONS.md  architecture.md  tradeoffs.md  pitch/
├── Dockerfile  docker-compose.yml
└── .github/workflows/ci.yml
```

---

## 4. Core library (modules M1 + M2)

### 4.1 Public API (`io.cachelab`)

```java
public interface Cache<K, V> extends AutoCloseable {
    Optional<V> get(K key);
    void put(K key, V value);                                   // default TTL, or never expires
    void put(K key, V value, Duration ttl);                     // per-entry TTL, ttl > 0
    V getOrLoad(K key, Function<? super K, ? extends V> loader); // single-flight
    boolean remove(K key);
    Optional<Duration> ttlRemaining(K key);
    int size();                                                 // may include expired-but-unswept entries (<= sweep interval)
    void clear();                                               // removals reported as EXPLICIT
    CacheStats stats();
    PolicyType policyType();
    void switchPolicy(PolicyType newPolicy);                    // runtime switch (6.2)
    PolicySnapshot<K> policySnapshot(int limit);                // for "Inside the cache"
    List<EntryView<K>> entries(int limit);                      // key, frequency, ttlRemaining (for Playground)
    String name();
    @Override void close();                                     // stops background threads
}
```

**Supporting public types**

| Type | Definition |
|---|---|
| `PolicyType` | Enum: `LRU`, `LFU`, `LFU_DECAY` |
| `RemovalCause` | Enum: `EXPLICIT`, `REPLACED`, `EVICTED`, `EXPIRED` |
| `RemovalListener<K,V>` | `void onRemoval(K key, V value, RemovalCause cause)`. Invoked **after** the lock is released, on the caller thread or on `removalExecutor` if configured. Exceptions are caught and logged to `System.Logger`; they never break the cache. |
| `AccessObserver<K>` | `void onAccess(K key, boolean hit)`. Invoked after lock release for every `get`/`getOrLoad` lookup. Used by the advisor and the key recorder. |
| `PolicySnapshot<K>` | Record: `PolicyType type`, `List<Entry<K>> entries`, where `Entry(K key, long frequency)`. LRU: most-recent-first, frequency 0. LFU / LFU_DECAY: highest frequency first. |
| `EntryView<K>` | Record: `K key`, `long frequency`, `Optional<Duration> ttlRemaining` |
| `CacheStats` | Record, see 4.7 |
| `Ticker` | `long read()` (nanoseconds); `static Ticker system()` |
| `CacheLoadException` | `extends RuntimeException` |

**`CacheBuilder<K,V>` options** (all validated, with clear messages):

| Option | Rule |
|---|---|
| `name(String)` | Default `"cache-N"` |
| `maximumSize(int)` | Must be ≥ 1 |
| `evictionPolicy(PolicyType)` | Default `LRU` |
| `defaultTtl(Duration)` | Must be > 0; absent means never expire |
| `concurrencyLevel(int)` | Power of two ≥ 1. `1` builds a `BoundedCache`; `> 1` builds a `SegmentedCache`. Must not exceed `maximumSize`. |
| `removalListener(...)` | — |
| `removalExecutor(Executor)` | — |
| `accessObserver(AccessObserver<? super K>)` | May be called multiple times |
| `ticker(Ticker)` | — |
| `sweepInterval(Duration)` | Default 100 ms, minimum 10 ms |
| `decayInterval(Duration)` | Default 10 s; used by `LFU_DECAY` only |

Example message: `"maximumSize must be at least 1, but was 0"`.

**Semantics** — document these in Javadoc and in the README:
- Null keys or values throw `NullPointerException`.
- `put` on an existing key:
  - replaces the value;
  - resets the TTL (to the given one, or the default);
  - counts as an access for the policy;
  - fires `REPLACED` with the old value;
  - never evicts.
- `get` on an expired entry:
  - returns empty;
  - removes the entry;
  - records one miss and one expiration;
  - fires `EXPIRED`;
  - never returns the stale value.
- Size never exceeds `maximumSize` after any call returns.

### 4.2 `Node<K,V>` (internal)

Fields, package-private:
- `final K key`
- `volatile V value`
- `long expiresAt` — `Long.MAX_VALUE` means never
- `int version` — incremented on every put
- `boolean removed`
- `Node prev, next`
- `FreqBucket bucket`
- `long frequency`
- `long lastAccess` — a monotonic access tick, **not** time

Method: `isExpired(long now)` returns `now - expiresAt >= 0`. This is overflow-safe.

### 4.3 Policies (M1)

```java
interface EvictionPolicy<K,V> {
    void onInsert(Node<K,V> n);        // new entry (n.frequency may be preset only via rebuildFrom)
    void onAccess(Node<K,V> n);        // hit or replace
    void onRemove(Node<K,V> n);        // explicit, expired, replaced-removal
    Node<K,V> pollVictim();            // unlink + return eviction victim; null if empty
    PolicySnapshot<K> snapshot(int limit);
    void rebuildFrom(List<Node<K,V>> nodesLeastRecentFirst); // used by switchPolicy
    default void decay() {}            // LFU_DECAY only; called by the engine on schedule
    void checkInvariants();            // throws IllegalStateException with details
    PolicyType type();
}
```

**Hard rule:** policies never read time, TTL or `expiresAt`. Code review rejects any `Ticker` import in policy classes.

**`IntrusiveList`**
- Doubly linked, with sentinel `head` and `tail`.
- Operations: `linkFirst`, `unlink`, `pollLast`, `peekLast`, `isEmpty`, `size`, and iteration from the front.

**`LruPolicy`**
- One list; the most recent entry is first.

| Method | Behaviour |
|---|---|
| `onInsert` | `linkFirst` |
| `onAccess` | Unlink, then `linkFirst` |
| `onRemove` | Unlink |
| `pollVictim` | `pollLast` |
| `rebuildFrom` | Insert in the given order (least recent first), so the most recent ends up first |

**`LfuPolicy`** — use the O(1) design of Shah, Mitra and Matani. **Do NOT use a `minFreq` integer.** It breaks when TTL removes the last entry at the minimum frequency.

Structure:
- A doubly linked list of `FreqBucket(long freq, IntrusiveList entries)`, kept in strictly ascending `freq`.
- Within a bucket, entries are most-recent-first.
- `node.bucket` points at the node's bucket.

| Method | Behaviour |
|---|---|
| `onInsert` | `frequency = 1`. Add to the bucket with freq 1, creating it right after the sentinel if needed. |
| `onAccess` | Let `f = frequency`. Move the node to bucket `f+1`: use the next bucket if its freq is `f+1`, otherwise create a new bucket right after the current one. Delete the old bucket if it is now empty. Then `frequency++`. |
| `onRemove` | Unlink; delete the bucket if empty. |
| `pollVictim` | The first bucket is always the lowest frequency; take its **least recent** node (`pollLast`) and delete the bucket if empty. |
| `snapshot` | Iterate buckets from highest to lowest (keep a tail sentinel), up to `limit`. |
| `rebuildFrom` | Group nodes by frequency (floor 1) with a temporary `TreeMap<Long, bucket>`. Insert each group in the given recency order, so ties keep least-recent-first eviction. Link the buckets in ascending order. |

All operations are O(1), except `rebuildFrom` at O(n log b), where b is the number of distinct frequencies.

**`checkInvariants` for both policies:**
- links are symmetric;
- there are no cycles;
- the total count equals the tracked size;
- LFU buckets are strictly ascending and none is empty;
- every node's `bucket.freq` equals its `frequency`.

**Reference models** (`src/testFixtures` or `src/test`):
- `ReferenceLru`: an access-ordered `LinkedHashMap`.
- `ReferenceLfu`: keeps `freq` and `lastAccessTick` per key; evicts the minimum `(freq, lastAccessTick)` by linear scan.

Both simulate **cache-aside** semantics for the differential test.

### 4.4 Engine: `BoundedCache<K,V>` (M2)

**State:**
- `HashMap<K, Node>`
- one `ReentrantLock` (non-fair)
- the `EvictionPolicy`
- an `ExpiryIndex`
- a `StatsRecorder`
- `long accessTick`
- listeners and observers

**Lookup and insert paths**
- Every successful access sets `node.lastAccess = ++accessTick`.
- On insert of a new key into a full cache:
  1. `expiry.purgeExpired(now, 16, removals)` — purge expired entries **before** evicting live ones;
  2. if still full, `policy.pollVictim()`, remove the victim from the map, record `EVICTED`.

**`getOrLoad` (single-flight)**
1. Lookup under the lock. If a live value is found: hit, return it.
2. Otherwise record **one miss for this call**, then use `ConcurrentHashMap<K, CompletableFuture<V>> inFlight`:
   - `computeIfAbsent` creates the future; the **creator** runs the loader **outside the cache lock**;
   - on success: `put(key, value)`, complete the future, record `loadSuccess` and the load time;
   - on failure: complete exceptionally, record `loadFailure`, do not cache;
   - in both cases, remove the future from `inFlight` in `finally`.
3. Waiting threads `join()`. Loader exceptions are rethrown as `CacheLoadException`, with the cause preserved.

**`ttlRemaining`**
- Returns empty if the key is missing, expired or has no TTL.

**`switchPolicy`** — see 6.2.

### 4.5 Expiry: `ExpiryIndex` and `Sweeper` (M2)

**`ExpiryIndex`**
- A `PriorityQueue<Ticket>` ordered by `expiresAt`, where `Ticket(long expiresAt, int version, Node node)`.
- `schedule(node)` adds a ticket, but only if `expiresAt != Long.MAX_VALUE`.
- A ticket is **stale** if `node.removed || ticket.version != node.version`. Stale tickets are discarded when polled.
- `purgeExpired(now, max, sink)` polls while the head is expired and ≤ `max` have been removed.
  - Each expired node is removed from the map and the policy, marked `removed`, and a `Removal(EXPIRED)` is added to the sink.
- **Compaction:** when `queue.size() > 2 * map.size() + 1024`, rebuild the queue from live nodes.
- Complexity: O(log n) per schedule. Document the timer wheel as future work in ADR-002.

**Lazy expiry**
- `get` checks `isExpired(now)` before touching the policy.

**`Sweeper`**
- One daemon thread per cache (a `ScheduledExecutorService`), named `cachelab-sweeper-<name>`.
- Every `sweepInterval`, it takes the lock and purges up to 256 entries.
- It also calls `policy.decay()` when the policy is `LFU_DECAY` and `decayInterval` has elapsed. The engine owns the time check; the policy stays time-free.
- It dispatches removal events after unlocking.
- `close()` shuts the sweeper down.

**Hard rule:** eviction and expiry are separate components. The policy is told only "node removed".

### 4.6 Locking and listener dispatch

**Rules**
1. Read `ticker.read()` **before** acquiring the lock.
2. Collect `Removal` records inside the lock; dispatch listeners **after** `unlock()`.
3. Call access observers after `unlock()`.
4. Never call user code (loaders, listeners, observers) while holding the lock.

**Reference shape**

```java
public Optional<V> get(K key) {
    requireNonNull(key, "key");
    long now = ticker.read();
    List<Removal<K,V>> removals = null; boolean hit; V value = null;
    lock.lock();
    try {
        Node<K,V> n = map.get(key);
        if (n == null) { hit = false; }
        else if (n.isExpired(now)) { removals = removeLocked(n, EXPIRED); stats.expiration(); hit = false; }
        else { n.lastAccess = ++accessTick; policy.onAccess(n); value = n.value; hit = true; }
    } finally { lock.unlock(); }
    if (hit) stats.hit(); else stats.miss();
    dispatch(removals); notifyObservers(key, hit);
    return Optional.ofNullable(value);
}
```

### 4.7 Statistics

**`StatsRecorder`** — `LongAdder` counters:
- `hits`, `misses`
- `evictions`, `expirations`
- `loadSuccess`, `loadFailure`, `totalLoadNanos`
- `puts`

**`CacheStats`** record:
- Fields: `hitCount`, `missCount`, `evictionCount`, `expirationCount`, `loadSuccessCount`, `loadFailureCount`, `totalLoadTimeNanos`, `putCount`.
- Derived: `requestCount()`, `hitRate()`, `missRate()` (0.0 when there are no requests, never NaN), `averageLoadPenaltyNanos()`.
- Also `minus(CacheStats other)`, so the server can compute deltas.

**Note:** `LongAdder.sum()` is not an atomic snapshot during concurrent updates. Invariants that compare counters are checked only after the threads have joined.

### 4.8 `SegmentedCache<K,V>` (M3)

- `N` = `concurrencyLevel` segments, each a `BoundedCache` with `ceil(maximumSize / N)` capacity.
  - Document that the total capacity can exceed `maximumSize` by at most `N-1`, **or** give the first `maximumSize % N` segments one extra slot so the total is exact. **Prefer exact.**
- Segment index: `spread(hash) & (N-1)`, where `spread(h) = h ^ (h >>> 16)`.
- One shared sweeper iterates the segments.
- `size` and `stats` are summed.
- `switchPolicy` switches each segment in turn.
- `policySnapshot` merges the segments' snapshots:
  - LFU: by frequency;
  - LRU: by `lastAccess`, using a global `AtomicLong` tick source shared across segments.
- Javadoc and ADR-001 document the trade-off: exact order within a segment, approximate globally.

---

## 5. Concurrency and performance (module M3)

### `StressHarness` (`io.cachelab.diagnostics`)

**`StressConfig`** fields:
- `impl` — `SINGLE_LOCK` or `SEGMENTED`
- `threads` — 1–64
- `duration` — ≤ 10 s
- `keySpace`
- `readRatio`
- `capacity`
- `policy`
- `seed`

**How a run works**
- All threads start together behind a `CountDownLatch` gate.
- Each thread keeps its own counts of gets and puts.
- Put values are strings `"k=<key>;t=<thread>;s=<seq>"`.
- Exceptions are collected in a `ConcurrentLinkedQueue`.
- A sampler thread records `size()` every 10 ms.
- After the join, `ThreadMXBean.findDeadlockedThreads()` is checked.

**`StressReport`:**
- `impl`, `threads`, `durationMs`
- `totalOps`, `opsPerSec`
- `List<InvariantResult(name, passed, detail)>`
- up to 10 exception summaries
- `deadlockFree`

### `InvariantChecker` — five invariants

1. **Size bound:** every sampled `size()` ≤ `maximumSize`, including the final one.
2. **Accounting:** `hits + misses` equals the total gets reported by the threads (checked after the join).
3. **No phantom values:** every value returned by `get(k)` parses to `k=<k>`.
4. **Structure intact:** `checkInvariants()` passes on every segment.
5. **No exceptions** in any thread.

### `StampedeTest`

- `run(threads = 200, loaderDelay = 200 ms)` against one missing key.
- The loader increments an `AtomicInteger`.
- **Result:**
  - `threads`
  - `loaderCalls` (expected 1)
  - `allSameValue`
  - `durationMs`

### `cache-bench` (JMH)

**Implementations**
- `single` (`BoundedCache`)
- `segmented` (16 segments)
- `syncLinkedHashMap` baseline (`Collections.synchronizedMap` over an access-ordered `LinkedHashMap` with `removeEldestEntry`)
- `caffeine` (reference ceiling)

**Workloads**
- `read90` / `mixed50` / `write90`, over a Zipf (s = 1.0) key stream from a precomputed array.

**Settings**
- Threads: 1, 4, 16, 32
- Capacity: 10,000; key space: 100,000
- Measurement: 1 fork, 3 warmup iterations and 5 measurement iterations. This is chosen for hackathon speed; document it next to the results.

**Exporter**
- `BenchExport` main class converts JMH JSON to `cache-server/src/main/resources/bench/bench-results.json`:

```json
{ "sample": false, "machine": { "cpu": "...", "cores": 8, "os": "...", "jvm": "..." },
  "results": [ { "impl": "segmented", "workload": "read90", "threads": 16, "opsPerSec": 0, "errorPct": 0 } ] }
```

- Until real results exist, ship a clearly marked file with `"sample": true`. The UI must show a "Sample data — run benchmarks" badge whenever `sample` is true.
- Gradle task: `./gradlew :cache-bench:jmh :cache-bench:exportBench`.

---

## 6. Intelligence (module M4)

### 6.1 `LfuDecayPolicy`

- Extends the LFU structure.
- **`decay()` halves every frequency:** `f -> max(1, f >> 1)` (the engine calls it every `decayInterval`, under the lock).
- **Linear-time merge:**
  - Halving preserves the order between buckets, so only adjacent bucket pairs merge (`2k` and `2k+1` become `k`, and `1` stays `1`).
  - Walk the buckets in ascending order.
  - For each new frequency, merge the member lists of the source buckets by `lastAccess`, **descending** (most recent first). This is a linear merge of two already-sorted lists.
  - Update each node's `frequency` and `bucket`.
- **Total cost:** O(n) per decay, done in one lock hold.
  - Measure the pause at 10,000 and 100,000 entries in a unit test (print it).
  - Document the pause in ADR-004.
- **Test:** after a decay, `checkInvariants()` passes and eviction order equals the reference model with the same halving applied.

### 6.2 Runtime policy switch

**`switchPolicy(newType)`, under the lock:**
1. Collect all live nodes.
2. Sort them by `lastAccess` ascending.
3. Build the new policy.
4. Call `rebuildFrom(sorted)`:
   - LFU and LFU_DECAY keep each node's existing `frequency` (floor 1);
   - LRU ignores it.
5. Replace the policy reference.

**Rules**
- O(n log n), once per switch.
- A no-op if the type is unchanged.
- Nodes keep their TTLs; the expiry index is untouched.

**Test:** no entries are lost, `checkInvariants()` passes, and a subsequent eviction matches the new policy's rules.

### 6.3 `ShadowCache` and `PolicyAdvisor`

**`ShadowCache<K>`**
- A keys-only simulator: `HashMap<K, Node<K,Object>>` plus an `EvictionPolicy` instance.
- Same capacity as the real cache.
- **Cache-aside behaviour:** `access(key)` → hit (`onAccess`), or miss (insert, evicting if full).
- TTL is ignored; document this.
- Has its own lock.
- Keeps windowed counts in 1-second buckets.

**`PolicyAdvisor`**
- Registered as an `AccessObserver` on the real cache.
- Feeds each key to one shadow per `PolicyType` — **including a shadow of the current policy**, so the comparison is shadow-to-shadow (apples to apples).
- Every second it evaluates the last 30 s.
- If the best candidate beats the current policy's shadow by more than **3.0 percentage points** for the whole window, it publishes `Recommendation(PolicyType current, PolicyType recommended, double expectedGainPts, int windowSec)`.
- Otherwise the recommendation is `Optional.empty()`.

**Rules**
- The advisor **never switches automatically**; the UI applies the switch.
- After a switch, reset the windows.

### 6.4 `KeyRecorder` and `OptimalReplay` (Bélády)

**`KeyRecorder`**
- An `AccessObserver` with a ring buffer of the last 200,000 lookup keys.
- `snapshot()` returns them in order.

**`OptimalReplay.hitRate(List<K> trace, int capacity)`**
- Optimal offline cache-aside (the MIN algorithm).
- **Next-use indices:** precompute `nextUse[i]` with a backward pass. When the key never recurs, use `Long.MAX_VALUE - i`, so every value is unique.
- **Resident set:** keep a `TreeMap<Long nextUse, K>` of resident keys.
  - **Hit:** replace the key's entry with its new `nextUse`.
  - **Miss:** if full, evict `lastKey()` (the furthest next use), then insert.
- Complexity: O(N log C).
- Result: hits ÷ N.

**Server usage**
- Every 5 s, asynchronously, per comparison group, over the group's recorded trace.
- Exposed as `optimalHitRate` (cumulative over the recorded trace).
- Tooltip text: *"Best possible hit rate for this traffic, if the cache knew the future. An upper bound, not a deployable policy."*

**Test:** over 100 random seeded traces (uniform, Zipf, loop), the optimal hit rate is ≥ the hit rate of `ShadowCache(LRU)`, `ShadowCache(LFU)` and `ShadowCache(LFU_DECAY)` with the same capacity.

---

## 7. Integrations (module M5)

### `cache-spring` — a Spring Boot starter

**Classes**
- `CacheLabCacheManager implements org.springframework.cache.CacheManager`
- `CacheLabSpringCache implements org.springframework.cache.Cache`

**Null handling**
- Spring may store nulls; our library rejects them.
- Store a private `NullValue` marker instead, and unwrap it on read.
- Document this.

**Auto-configuration**
- `@AutoConfiguration`, conditional on the `cachelab.enabled` property (default `true`).
- Register it in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

**Properties**
```
cachelab.caches.<name>.maximum-size=10000
cachelab.caches.<name>.policy=lfu|lru|lfu_decay
cachelab.caches.<name>.default-ttl=10m
cachelab.caches.<name>.concurrency-level=16
```

**Micrometer binder**
- `CacheLabMetrics` binds these meters with a `cache=<name>` tag:
  - `cache.gets{result=hit|miss}`
  - `cache.evictions`
  - `cache.expirations`
  - `cache.size`
  - `cache.loads{result=success|failure}`
- Active only when Micrometer is on the classpath.

**Publishing**
- `maven-publish` with sources and Javadoc jars, to `build/repo` (local) and GitHub Packages (configured through environment variables).

### `examples/formulary-service`

A small Spring Boot app, **under 100 lines** of main code:
- `DrugRepository` simulates a database with `Thread.sleep(30)` per lookup, over 50,000 drug IDs.
- `DrugService.find(id)` is annotated `@Cacheable("formulary")`.
- Endpoints:
  - `GET /drugs/{id}`
  - `GET /stats` — cache stats plus the average response time over the last 1,000 calls
- Actuator is enabled, with `/actuator/prometheus` exposed.

**`LatencyComparisonTest`**
- Measures the average time of 2,000 Zipf-distributed lookups with and without the cache.
- Prints the numbers and asserts that the cached run is at least 10× faster.
- Report the **measured** numbers in the README; never invent them.

---

## 8. Demo server (module M6) — `cache-server`

### 8.1 Components

**`CacheRegistry`**
- Holds named caches, each with a `CacheConfig(name, policy, capacity, defaultTtlMs, concurrencyLevel, group)`.
- **Groups** are comparison sets that share a workload. Each group has:
  - a `KeyRecorder`;
  - a `PolicyAdvisor`, attached to the first cache of the group.
- **Default boot state:** group `demo` with `lru-A` (LRU) and `lfu-A` (LFU), capacity 1,000, no TTL.

**Other components**

| Component | Responsibility |
|---|---|
| `SimulationService` | Runs workloads (section 9) |
| `DemoActService` | Runs guided demo acts (9.4) |
| `MetricsPublisher` | Pushes the SSE stream (8.3) |
| `EventRing` | Last 200 removal events, fed by the removal listeners |
| `LatencyRecorder` | Per cache, fixed log-scale buckets from 0.1 µs to 100 ms; reports p50 and p99 over the last 10 s |
| `StressService` | Runs one stress job at a time; a second request returns `409` |
| `BenchService` | Serves the benchmark results file |

**Global rules**
- Errors are returned as RFC 7807 `ProblemDetail`.
- Requests are validated with Jakarta Bean Validation.
- CORS allows `http://localhost:5173` under the `dev` profile.
- The built dashboard is served from `classpath:/static`, with an SPA fallback: unknown non-API paths return `index.html`.

### 8.2 REST API

Keep `docs/api/openapi.yaml` in sync. springdoc serves Swagger UI at `/swagger-ui`.

| Method | Path | Request | Response |
|---|---|---|---|
| GET | `/api/health` | — | `{status:"UP"}` |
| GET | `/api/caches` | — | `[CacheConfig + live stats]` |
| POST | `/api/caches` | `CacheConfig` | `201 CacheConfig` |
| DELETE | `/api/caches/{name}` | — | `204` |
| GET | `/api/caches/{name}/entries?limit=50` | — | `[{key, frequency, ttlRemainingMs}]` |
| GET | `/api/caches/{name}/entries/{key}` | — | `{hit, value?, ttlRemainingMs?}` (records a normal get) |
| PUT | `/api/caches/{name}/entries/{key}` | `{value, ttlMs?}` | `204` |
| DELETE | `/api/caches/{name}/entries/{key}` | — | `{removed}` |
| GET | `/api/caches/{name}/snapshot?limit=20` | — | `PolicySnapshot` |
| POST | `/api/caches/{name}/policy` | `{policy}` | `CacheConfig` |
| POST | `/api/caches/{name}/reset-stats` | — | `204` |
| POST | `/api/groups/{group}/advisor/apply` | — | `{switchedTo}` or `409` if there is no recommendation |
| POST | `/api/simulations` | `{group, pattern, opsPerSec, readRatio, durationSec, seed, params?}` | `{id}` |
| DELETE | `/api/simulations/{id}` | — | `204` |
| POST | `/api/demo/acts/{n}/start` | — | `{act, title, phases:[{pattern, durationSec, caption}]}` |
| POST | `/api/demo/stop` | — | `204` |
| POST | `/api/stress` | `StressConfig` | `StressReport` |
| POST | `/api/stress/stampede` | `{threads, loaderDelayMs}` | `StampedeResult` |
| GET | `/api/bench` | — | the bench results file |
| POST | `/api/traces` | multipart CSV | `{traceId, rows}` |
| POST | `/api/traces/{id}/replay` | `{capacity, policies[]}` | `[{policy, hitRate, hits, misses, evictions}]` plus `optimalHitRate` |
| GET | `/api/reports/latest.{csv\|json}` | — | the last simulation's per-cache summary |
| GET | `/api/metrics/stream` | — | `text/event-stream` |

**Stats reset**
- `reset-stats` can't zero a `LongAdder` safely. Instead, store a **baseline** `CacheStats` and report `current.minus(baseline)`.

### 8.3 Metrics stream (schema v2, final)

**Delivery**
- Send one event, named `metrics`, every 500 ms.
- The fake generator is available through `cachelab.metrics.fake=true`. It is used only while developing Step 1–2, and defaults to `false` from Step 3 onward.

**Payload**
```json
{
  "ts": 1790000000500,
  "caches": [{
    "name": "lru-A", "group": "demo", "policy": "LRU", "size": 1000, "capacity": 1000,
    "hits": 812344, "misses": 190211, "hitRate": 0.810, "hitRateWindow10s": 0.774,
    "evictions": 180102, "expirations": 9120, "opsPerSec": 5012,
    "getP50Micros": 0.8, "getP99Micros": 6.4,
    "dbCallsAvoided": 812344, "latencySavedMs": 9748128, "estCostSaved": 40.62
  }],
  "groups": [{
    "name": "demo", "caches": ["lru-A", "lfu-A"], "optimalHitRate": 0.861,
    "advisor": { "current": "LRU", "recommended": "LFU", "expectedGainPts": 7.4, "windowSec": 30 }
  }],
  "simulation": { "id": "s-12", "running": true, "group": "demo", "pattern": "SCAN_POLLUTION",
                  "act": 1, "phaseIndex": 1, "phaseCount": 3,
                  "phaseCaption": "A one-off scan floods the cache — watch LRU's line",
                  "phaseStartedTs": 1790000000000 },
  "events": [{ "ts": 1790000000400, "cache": "lru-A", "key": "drug:4411", "cause": "EVICTED" }]
}
```

**Field rules**
- `advisor`, `optimalHitRate` and `simulation` may be `null`.
- `events` holds only the events since the last tick, at most 50.
- Rates are 0–1.
- Counters are monotonic, except after a `reset-stats`.
- `hitRateWindow10s` comes from a ring of the last 20 per-tick deltas.

### 8.4 SSE lifecycle

- Each connection gets its own `SseEmitter` with timeout `0` (never), kept in a `CopyOnWriteArrayList`.
- Remove the emitter on completion, timeout or error, **and** when a send fails.
- Build the payload **once** per tick, then send it to every emitter.
- The publisher runs on a single scheduled thread.

**Test:** connect, receive at least 2 events, disconnect; the emitter count returns to 0.

---

## 9. Workload lab (module M7)

### 9.1 Key streams (`workload` package)

**Interface:** `KeyStream { String next(SplittableRandom rnd, long opIndex, long elapsedMs); }`

The Zipf sampler precomputes a CDF over the key space and picks each key by binary search.

| Pattern | Parameters (defaults) | Expected on screen (capacity 1,000; key space 10,000) |
|---|---|---|
| `UNIFORM` | keySpace 10k | ≈10% hit rate for every policy |
| `ZIPF` | s = 1.0 | Both high; LFU slightly ahead |
| `SCAN_POLLUTION` | Zipf, plus a sweep of 5,000 cold keys (`cold:<n>`) interleaved 1:1 with hot traffic | LRU drops sharply during the sweep; LFU holds |
| `LOOP` | keys 0–1,099 cyclic | LRU → ≈0%; LFU keeps most of the loop |
| `SHIFTING_HOTSPOT` | Zipf, key offset rotated by 2,000 every 20 s | LRU recovers fast; LFU lags; LFU_DECAY recovers |
| `TTL_BURST` | Zipf; 30% of puts use a 2 s TTL | Expirations rise equally across policies |
| `FORMULARY` | 50,000 drug IDs (`drug:<n>`), Zipf s = 1.1, a "morning surge" boosting 200 common drugs for 10 s every 40 s | Realistic healthcare traffic |
| `PROVIDER_DIRECTORY` | 20,000 provider IDs (`prov:<region>:<n>`), a regional hotspot moving every 30 s | Realistic healthcare traffic |

### 9.2 Simulation runner

- **One generator thread per simulation**, for determinism.
- **Rate control:** paces operations to `opsPerSec` using `LockSupport.parkNanos` in 1 ms batches. `opsPerSec = 0` means unthrottled.
- **Each operation:**
  - With probability `readRatio`, a **read**: time `cache.get(key)` into the `LatencyRecorder`.
    - On a miss, `SimulatedDatabase.load(key)`, then `put`.
    - `TTL_BURST` applies its TTL rule on that put.
  - Otherwise, a **write**: `put(key, newValue)`.
  - Every key is applied to **every cache in the group, in the same order.**
- **Determinism:**
  - The same seed and parameters give identical hit and miss counts, **for non-TTL patterns**. TTL patterns depend on wall-clock time.
  - Test this with 2 runs × 50,000 ops, unthrottled.

### 9.3 Simulated database and cost model

**`SimulatedDatabase`**
- Does **not** sleep.
- Accounts a latency drawn from a seeded uniform distribution of 5–20 ms per call, and counts calls.

**`CostModel`**
- `dbCallsAvoided = hits`
- `latencySavedMs = hits × meanDbLatencyMs`
- `estCostSaved = dbCallsAvoided / 1000 × costPerThousandCalls`
  - `costPerThousandCalls` is configurable, default `0.05`; the currency label is configurable, default `"$"`.
- The UI labels all three as estimates, with the assumptions shown in a tooltip.

### 9.4 Guided demo acts (`DemoActService`)

**Starting an act**
- Resets the relevant group: it recreates the caches and resets the stats.
- Runs the phases in sequence with seed 42 at 5,000 ops/s.
- Sets `simulation.act`, `phaseIndex` and `phaseCaption` in the SSE stream.

| Act | Title | Group caches | Phases (pattern, seconds, caption) |
|---|---|---|---|
| 1 | It works | LRU, LFU | `ZIPF` 20 s "Both policies learn the hot keys" → `SCAN_POLLUTION` 15 s "A one-off scan floods the cache — watch LRU's line" → `ZIPF` 15 s "LRU recovers; LFU never lost its hot keys" |
| 2 | It is smart | LRU, LFU, LFU_DECAY | `SHIFTING_HOTSPOT` 60 s "The popular keys keep changing. Plain LFU clings to old favourites; LFU with decay adapts. Watch the advisor." |
| 3 | TTL is independent | LRU, LFU | `TTL_BURST` 30 s "Expirations rise equally under every policy — only evictions differ" |
| 4 | It is safe | (no workload) | The dashboard navigates to the Concurrency Lab, calls `/api/stress` for single and segmented at 32 threads × 5 s, then `/api/stress/stampede` |

### 9.5 Trace replay and reports

**Trace upload**
- Accepts `key` per line, or `timestamp,key` with an optional header.
- Up to 1,000,000 rows; 20 MB maximum.
- Rejects empty or oversized files with a clear `ProblemDetail`.
- Traces are stored in memory, keeping at most 3; the oldest is evicted.

**Replay**
- Runs **offline and unthrottled**: through `ShadowCache` per policy, plus `OptimalReplay`.
- Must finish in under 10 s for 100,000 rows.

**Sample data**
- `samples/formulary-trace.csv`: 100,000 rows generated by a small script from the `FORMULARY` stream, seed 7.

**Reports**
- `latest.csv` / `latest.json` contain the per-cache final stats of the last simulation, plus its parameters.

---

## 10. Dashboard (module M8)

### 10.1 Theme: "Circuit & Motherboard"

**Concept**
- The dashboard is a live circuit board:
  - cards are chips;
  - statuses are LEDs;
  - policy selectors are DIP switches;
  - electrons flow along copper traces in the background.
- The theme is set with `[data-theme]` on `<html>`: `pcb-blue` (default) or `pcb-amber`.
- The user's choice is persisted in `localStorage` (wrap access in try/catch).

**Theme tokens**

| Token | `pcb-blue` | `pcb-amber` |
|---|---|---|
| `--pcb-bg` | `#061321` | `#140C04` |
| `--pcb-surface` | `#0B2135` | `#22160A` |
| `--pcb-surface-2` | `#10283F` | `#2C1D0D` |
| `--trace` | `#1D5B7C` | `#7A4A12` |
| `--trace-glow` | `#38BDF8` | `#FBBF24` |
| `--electron` | `#4ADE80` | `#FB923C` |
| `--pad` | `#B8C4D0` | `#E8C27A` |
| `--text` | `#E6F1FA` | `#FFF4E0` |
| `--text-muted` | `#8FA9BF` | `#C9A777` |

**Shared tokens (both themes)**

| Purpose | Colours |
|---|---|
| Policy (Okabe-Ito, colour-blind safe) | LRU `#56B4E9` (solid line), LFU `#E69F00` (dashed), LFU_DECAY `#009E73` (dotted), Optimal `#9CA3AF` (long dash) |
| Status | Good `#4ADE80`, Warn `#FBBF24`, Critical `#F87171` |

**Typography and layout**
- Fonts:
  - Space Grotesk for headings;
  - Inter for body text;
  - JetBrains Mono for numbers and code, with `tabular-nums`.
- Type scale: 14 / 16 / 20 / 28 px, on an 8 px spacing grid.
- Focus ring: 2 px `--trace-glow`.
- All text must meet WCAG AA contrast.

### 10.2 Circuit background

**1. Generator script** — `scripts/generate-circuit.mjs` (`npm run generate:circuit`)
- Seeded mulberry32 PRNG (seed `20260930`), so the board is identical on every build.
- 1440×900 board on a 24 px routing grid.
- About 40 traces, orthogonal with 45° bends, mostly edge to edge.
- Vias and pads drawn as circles at the trace ends and bends.
- 3–4 IC outlines with pin rows.
- 16 traces marked as electron lanes.
- Output: `src/theme/circuit.generated.ts` (committed).

**2. `CircuitBackground.tsx`**
- A fixed full-viewport SVG: `z-index: -1`, `pointer-events: none`, `aria-hidden`, `preserveAspectRatio="xMidYMid slice"`.
- A radial vignette so foreground cards stay readable.

**3. Electrons (pure CSS)**
- Each electron lane is a `<path pathLength="100">` with:
  - stroke `--electron`, width 2.5, round cap;
  - `stroke-dasharray: 1.5 98.5`;
  - `@keyframes flow { to { stroke-dashoffset: -100 } }`;
  - 7–12 s per lap, with staggered negative delays.
- **One** `drop-shadow` glow filter on the electrons **group**, not on each path.

**4. Performance and accessibility**
- Only `stroke-dashoffset` animates.
- Set `animation-play-state: paused` when `document.hidden`.
- Under `prefers-reduced-motion: reduce`, show the static board with no electrons.
- A user toggle "Circuit animation" (persisted) turns it off.

### 10.3 Components (`src/components/`)

| Component | Behaviour |
|---|---|
| `ChipCard` | Opaque surface; thin `--trace` border; pin notches on the left and right edges (pseudo-elements); a silkscreen label top-left in small mono uppercase (`U1 · HIT RATE`); an optional `info` popover |
| `MetricTile` | Large mono value with a faint glow in its accent colour; label; optional delta; an `InfoPopover` with a one-sentence explanation |
| `ConnectionLed` | `live` = green glow; `connecting` / `reconnecting` = blinking amber; `offline` = red; announces changes via `aria-live="polite"` |
| `PolicyBadge` | The policy name in its policy colour, plus a line-style glyph |
| `DipSwitch` | A radiogroup of policies styled as DIP switches; keyboard: arrow keys and space |
| `InfoPopover` | A button that opens a popover (`role="dialog"`); Esc closes it; focus returns to the button |
| `LedRow` | Five LEDs for the invariants; they light in sequence (150 ms stagger) on a pass, and show red with a detail tooltip on a fail |
| `CodeSnippet` | Mono block with a copy button and a "Copied" toast |
| `SampleBadge` | Shown on any view that uses sample or estimated data |
| Feedback states | `EmptyState`, `Skeleton`, `Toast` |

### 10.4 Data layer

**Types and API**
- `src/api/types.ts` mirrors schema v2 **exactly**.
- `src/api/client.ts` holds typed fetch wrappers for every endpoint in 8.2.
- Errors are shown as toasts, using the `ProblemDetail` message.

**`useMetricsStream()`**
- Uses `EventSource` on `/api/metrics/stream`, listening to event `metrics`.
- Tracks the connection state.
- Keeps a ring of 120 snapshots (60 s).
- Re-renders at most twice per second.
- Guards the payload shape, dropping bad events.
- Reconnects with backoff: 1, 2, 4, then a cap of 8 s.

**Tests**
- Vitest tests cover the reducer, the shape guard and the formatters.

### 10.5 Pages

**App shell**
- Sidebar with icons, collapsing to icons only below 1100 px.
- Top bar containing:
  - the "CacheLab" silkscreen wordmark;
  - `ConnectionLed`;
  - "Start guided demo";
  - theme switch;
  - animation toggle.

**1. Overview**
- **Data bus hero** (10.6).
- **KPI tiles** for the selected cache (via a selector):
  - hit rate, miss rate
  - ops/sec
  - size ÷ capacity
  - evictions, expirations
  - p99 get latency
- **Hit rate over time:** `hitRateWindow10s` per cache, in the policy colour and line style, with direct end labels.
  - Vertical markers labelled with the phase caption whenever `phaseIndex` changes.
- **Removals by cause:** evictions/s vs expirations/s per cache, as grouped bars.
- **Cost panel:** database calls avoided, latency saved, estimated cost saved, and an editable price per 1,000 calls. Marked as estimates.
- **Text summary** under each chart for screen readers.

**2. Policy Race**
- Group selector, pattern picker, ops/sec slider, and start/stop.
- Side-by-side hit-rate lines plus the optimal line.
- **Advisor banner:** "Switch to LFU with decay: +7.4 points (last 30 s)" with an **Apply** button.
- **"Inside the cache":**
  - LRU: a strip of the 20 most recent keys;
  - LFU: horizontal bars of the top 20 frequencies;
  - polled every 1 s from `/snapshot`, only while visible.
- **Event log:** the last 50 removals, with time, key, cause (colour and icon) and cache.

**3. Concurrency Lab**
- Implementation toggle (single / segmented), a thread slider (1–64) and a duration slider (1–10 s).
- A **Run stress test** button, and a `LedRow` for the five invariants with their details.
- Throughput chart from `/api/bench`: ops/sec vs threads, one line per implementation, per workload tab.
- **Stampede panel:** "200 threads asked for the same missing key → loader ran **1** time."

**4. Playground**
- Create a cache (policy, capacity, default TTL).
- Get / put / delete form; the result shows hit or miss.
- Entries table with live TTL countdowns (poll `/entries` every 1 s).
- A `DipSwitch` to switch the policy live.

**5. Trace Replay**
- CSV drag-and-drop.
- A "Use sample formulary trace" button.
- Choose capacity and policies.
- Results as bars: hit rate per policy, plus the optimal hit rate.
- Download the report.

**6. Integrations**
- `CodeSnippet`s for:
  - the Gradle and Maven dependency;
  - the builder example;
  - `@Cacheable` with `application.properties`;
  - the Prometheus endpoint.
- Plus a short "why use CacheLab" list and the measured `formulary-service` latency (from the README).

### 10.6 Data bus (the signature element)

**Layout**
- An SVG strip with three chips (`CLIENT`, `CACHE`, `DB`) joined by traces.

**Electrons**
- Spawned by `requestAnimationFrame` at a rate proportional to the selected cache's `opsPerSec`.
- Legend: "1 electron ≈ N requests"; capped at 30 on screen at once.
- Each electron is a hit with probability `hitRateWindow10s`:
  - **Hit:** CLIENT → CACHE → back to CLIENT, in green (`--good`).
  - **Miss:** CLIENT → CACHE → DB → back, in orange (`--warn`).
- Animate transforms only.
- Pause when the tab is hidden.

**Reduced motion**
- Show static arrows labelled with the hit % and miss %.

**Caption**
- A live caption beneath: "84% of requests never reached the database."

### 10.7 Guided demo mode

**Starting the demo**
- "Start guided demo" opens a presenter overlay (bottom sheet) and calls `POST /api/demo/acts/1/start`.

**Presenter overlay**
- Shows the act title, the phase caption (from SSE) and a progress bar.
- Controls: → next act, ← previous act, Space to pause (calls `/api/demo/stop`), Esc to exit.

**Navigation between acts**
- Acts 1–3 navigate to the Policy Race page with the right group preselected.
- Act 4 navigates to the Concurrency Lab and runs the tests automatically, showing the results.

**Done when:** the whole demo runs without touching any other control.

### 10.8 UX and accessibility rules

- Every metric has an `InfoPopover` with one plain sentence.
- Every data view has an empty state, a loading skeleton and an error state.
- Full keyboard navigation with visible focus.
- ARIA labels on all controls.
- Charts include a text summary.
- Layout works from 1280 px down to tablet width.
- Charts update at most twice per second; each series is capped at 120 points.
- Lighthouse accessibility score ≥ 90 on the Overview page. Run it if possible, otherwise do a manual checklist.

---

## 11. Packaging, docs and pitch (module M9)

### Packaging
- **`scripts/build-all.sh`:**
  1. `npm ci && npm run build` in `dashboard/`;
  2. copy `dist/` into `cache-server/src/main/resources/static` (gitignored);
  3. `./gradlew :cache-server:bootJar`.
- **`Dockerfile`** (multi-stage):
  1. Node 20 builds the dashboard;
  2. a Gradle JDK 21 image builds the jar;
  3. Temurin 21 JRE runs it.
- **`docker-compose.yml`:** one service on port 8080.
- Result: `docker compose up` gives the full demo at `http://localhost:8080`.

### `README.md` — in this order
1. The pitch, and a GIF placeholder (`docs/pitch/demo.gif`, recorded manually).
2. Quickstart in 3 commands.
3. Library usage: the builder example and `@Cacheable`.
4. Cache semantics.
5. Complexity table: get and remove O(1) for all policies; put O(1) plus O(log n) for TTL scheduling; LFU-decay O(n) per decay; policy switch O(n log n) once.
6. Architecture diagram (Mermaid).
7. Benchmark table (from the real results; if they are sample data, say so).
8. Testing summary (the test count and what they cover).
9. Modules.
10. License.

### ADRs (`docs/adr/`)
- **001** — Single lock first, lock striping second.
- **002** — Expiry separate from policy (plus timer wheel as future work).
- **003** — LFU with frequency buckets instead of `minFreq`.
- **004** — LFU decay by halving with a linear merge (pause measurements).
- **005** — Advisor recommends and never auto-switches; shadows are compared to shadows.

### Other docs
- **`docs/tradeoffs.md`**: single lock vs segments; heap vs timer wheel; exact vs approximate LRU; shadow memory cost.
- **`docs/pitch/`**:
  - `slides-outline.md` (8–10 slides);
  - `demo-script.md` (the 5-minute, 4-act script);
  - `qa.md` (the judge questions and answers below).

**Judge Q&A to include in `qa.md`**
- Why is LFU O(1)?
- Why not `LinkedHashMap`?
- Why not a read-write lock? (Because a `get` mutates the order, so it is a write.)
- What does segmentation cost?
- When does LFU lose to LRU?
- How do you know it is correct?
- How does this compare to Caffeine?
- What happens when a listener is slow?

---

## 12. Execution plan: 5 steps with gates

### Step 1 — Foundation and theme shell (M0 + M8 part 1)

**Build**
- Repo and build setup:
  - the full section 3 layout, with placeholder modules that compile;
  - CI (a Java job and a dashboard job);
  - Spotless and JaCoCo;
  - `CLAUDE.md` and `PROGRESS.md`;
  - `docs/DECISIONS.md`, and ADR-001 and ADR-002.
- Library contracts:
  - all public API types from 4.1, with full Javadoc;
  - `CacheBuilder` validation fully implemented; `build()` throws `UnsupportedOperationException` until Step 2;
  - `CacheStats` fully implemented;
  - `Node` and `EvictionPolicy`;
  - `FakeTicker` in the test fixtures.
- API documents: `docs/api/metrics.schema.json` (schema v2) and the `openapi.yaml` stub.
- Server: `/api/health` and the fake SSE generator.
  - Groups `demo` (`lru-A`, `lfu-A`).
  - Bounded random-walk hit rates, with `lru-A` dipping to about 0.35 for 5 s every 30 s.
  - Monotonic counters; seeded.
- Dashboard:
  - theme tokens, fonts and the circuit background with electrons;
  - all components from 10.3;
  - the shell with 6 routes;
  - the Overview page with KPI tiles and the hit-rate chart on fake data;
  - the other pages as `ChipCard` stubs naming the step that delivers them.

**Gate 1**
- `./gradlew spotlessCheck build` passes.
- `./gradlew :cache-core:dependencies --configuration runtimeClasspath` shows no dependencies.
- `cd dashboard && npm run lint && npm run typecheck && npm test -- --run && npm run build` passes.
- `curl -N localhost:8080/api/metrics/stream` shows events that match the schema.
- The dashboard shows:
  - moving electrons;
  - the LED green;
  - tiles and the chart updating;
  - the `lru-A` dip.
- Stopping the server turns the LED amber; restarting it turns it green.
- Reduced motion shows a static board.

### Step 2 — Core engine (M1, M2, M6 part 1, M8 part 2)

**Build**
- Policies and engine:
  - `IntrusiveList`, `LruPolicy`, `LfuPolicy` with `checkInvariants` and `rebuildFrom`;
  - reference models and differential tests;
  - `BoundedCache`, `ExpiryIndex`, `Sweeper`, `StatsRecorder`, listeners and observers, `getOrLoad`, `ttlRemaining`, `entries`, `policySnapshot`.
- Server:
  - `CacheRegistry` with **real** caches;
  - the entries, snapshot, cache CRUD and reset-stats endpoints;
  - `EventRing`.
  - The fake generator is still the default stream.
- Dashboard: the Playground page (fully working against real caches), plus the Overview charts completed.

**Gate 2**
- **Unit tests pass:**
  - LRU: eviction order, capacity 1, replace-never-evicts.
  - LFU: tie-breaks, bucket deletion, TTL removal of the last minimum-frequency node.
- **Differential tests:** 10,000 sequences × 200 ops for LRU and LFU, all equal to the reference.
- **TTL tests** (with `FakeTicker`, no `Thread.sleep`):
  - a `@ParameterizedTest` over LRU and LFU;
  - expired read = miss + expiration;
  - purge-before-evict;
  - replace resets TTL.
- **Listener re-entry:** a listener that calls `get` does not deadlock.
- **Loading:** 100 threads calling `getOrLoad` on the same key → the loader runs once.
- **Playground (manual check):** put with TTL; watch the countdown; the entry disappears; a get then reports a miss.

### Step 3 — Integration and MVP (M3 part 1, M4 part 1, M6 part 2, M7, M8 part 3)

**Build**
- Concurrency and policies:
  - `SegmentedCache`, `StressHarness`, `InvariantChecker`, `StampedeTest`;
  - `LfuDecayPolicy`;
  - `switchPolicy`.
- Server:
  - `MetricsPublisher` on **real** stats (fake off by default), with deltas, the 10 s window, `LatencyRecorder`, `CostModel` fields and `events`;
  - the `/api/stress`, `/api/stress/stampede` and `/policy` endpoints.
- Workload lab:
  - all key streams;
  - `SimulationService`, `SimulatedDatabase`;
  - the `/api/simulations` endpoints.
- Dashboard:
  - the Policy Race page (without the advisor and optimal line);
  - the Concurrency Lab page (stress, stampede, `LedRow`);
  - the cost panel.

**Gate 3 (MVP — must not slip)**
- **Stress:** 32 threads × 5 s passes 20 consecutive runs for both implementations, and no deadlocks are found.
- **Determinism:** the non-TTL workload test passes.
- **LFU decay:** unit tests pass, including the pause measurement.
- **Policy switch:** tests pass.
- **Live check:**
  1. Start `ZIPF` then `SCAN_POLLUTION` on group `demo`.
  2. The LRU line drops and LFU holds.
  3. The event log fills.
  4. The stress test lights five green LEDs.
  5. The stampede shows loader = 1.
- **SSE:** the emitter lifecycle test passes.

### Step 4 — Stand-out features (M3 part 2, M4 part 2, M5, M8 part 4)

**Build**
- Intelligence:
  - `ShadowCache`, `PolicyAdvisor`, `KeyRecorder`, `OptimalReplay`;
  - the group fields in SSE;
  - `/advisor/apply`.
- Benchmarks: `cache-bench` JMH plus the exporter and `/api/bench`. Run the benchmarks if time allows; otherwise ship the sample file flagged `sample: true`.
- Integrations:
  - `cache-spring` (starter, Micrometer binder, publishing);
  - `examples/formulary-service` with `LatencyComparisonTest`.
- Trace replay: the endpoints plus `samples/formulary-trace.csv`.
- Dashboard:
  - the advisor banner and optimal line;
  - "Inside the cache";
  - the bench chart;
  - the Trace Replay and Integrations pages;
  - the Data bus;
  - `DemoActService` and guided demo mode.

**Gate 4**
- **Optimal replay:** the optimal ≥ every shadow policy on 100 random traces.
- **Advisor:** recommends LFU (or LFU_DECAY) during `SCAN_POLLUTION`, and LFU_DECAY or LRU during `SHIFTING_HOTSPOT`.
  - Verify with a deterministic server-side integration test that uses shadow counts, not wall time.
- **Spring starter:** the `@Cacheable` example works.
- **Latency:** `LatencyComparisonTest` passes, and its measured numbers are printed.
- **Metrics:** `/actuator/prometheus` in the example shows the `cache_*` meters.
- **Trace replay:** a 100,000-row replay takes under 10 s.
- **Guided demo:** runs acts 1–4 end to end with no other clicks.
- **Data bus:** animates and respects reduced motion.

### Step 5 — Polish and pitch (M9 + hardening)

**Build**
- `scripts/build-all.sh`, the `Dockerfile` and `docker-compose.yml`.
- The full README, ADR-003 to ADR-005, `tradeoffs.md`, `architecture.md` and the `docs/pitch/*` files.
- Javadoc published by a Gradle task.
- Hardening and accessibility:
  - run the whole test suite 3 times (catches flaky tests);
  - fix any flaky test found;
  - accessibility pass (Lighthouse or the manual checklist);
  - performance pass: with 4 caches and the guided demo running, the page stays smooth (no long tasks over 50 ms in the profiler, if checkable).

**Gate 5 (final)**
- `docker compose up` on a clean checkout serves the full demo.
- Every test is green.
- CI is green.
- `PROGRESS.md` shows all modules done.
- The final report lists:
  - every requirement from section 1 with where it is demonstrated;
  - the test count;
  - the benchmark status (real or sample);
  - any open issues.

---

## 13. Test plan summary

| Suite | Module | Technique |
|---|---|---|
| Builder validation, `CacheStats` | M0 | JUnit, AssertJ |
| LRU/LFU unit + `checkInvariants` | M1 | JUnit |
| Differential vs reference models | M1 | Seeded loops, 10k × 200 ops |
| TTL, independence (parameterized), purge-before-evict | M2 | `FakeTicker` |
| Listener re-entry, `getOrLoad` single-flight | M2 | Threads + latch |
| Stress (5 invariants), deadlock, stampede | M3 | `StressHarness` |
| LFU decay merge + pause, `switchPolicy` | M4 | JUnit vs reference |
| Optimal ≥ all policies | M4 | 100 seeded traces |
| Spring adapter, Micrometer, latency comparison | M5 | `@SpringBootTest` |
| Endpoints, SSE lifecycle, advisor integration | M6 | `@SpringBootTest` + WebTestClient/MockMvc |
| Workload determinism, pattern outcome smoke tests | M7 | JUnit |
| Stream reducer, shape guard, formatters, key components | M8 | Vitest + Testing Library |

---

## 14. Final acceptance checklist

- [ ] Every minimum requirement in section 1 is demonstrable in the running app.
- [ ] `cache-core` has zero runtime dependencies and full Javadoc.
- [ ] All tests are green, three runs in a row.
- [ ] Guided demo acts 1–4 run end to end.
- [ ] Theme: circuit background with electrons; blue and amber themes; reduced-motion and animation toggle both work.
- [ ] The Data bus reflects the live hit rate.
- [ ] The advisor, optimal line, stress LEDs and stampede all work.
- [ ] The Spring starter works in the example service, with **measured** latency numbers.
- [ ] `docker compose up` works from a clean clone.
- [ ] README, ADRs 001–005, tradeoffs and pitch docs are complete.
- [ ] No invented numbers anywhere: benchmarks and latencies are measured or clearly marked as sample/estimate.
