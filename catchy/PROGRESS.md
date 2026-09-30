# PROGRESS

**Current step:** Step 2 — Core engine — **Gate 2 passed** (2026-09-30). The user asked to build
Steps 2–5 and push each to `origin/main`; continuing with Step 3.

## Done
- **Step 1** — foundation and theme shell (Gate 1 passed).
- **Step 2** — core engine:
  - [x] `IntrusiveList`, `LruPolicy`, `LfuPolicy` (frequency buckets, no `minFreq`), `checkInvariants`, `rebuildFrom`
  - [x] Reference models (`ReferenceLru`, `ReferenceLfu`) + differential tests (10,000 × 200 ops per policy)
  - [x] `BoundedCache`: `ExpiryIndex` (versioned tickets, compaction), `Sweeper`, `StatsRecorder`, listeners/observers after unlock, single-flight `getOrLoad`, `ttlRemaining`, `entries`, `policySnapshot`, `switchPolicy`
  - [x] `CacheBuilder.build()` (single-lock caches)
  - [x] Server: `CacheRegistry` (real caches, demo group), cache CRUD / entries / snapshot / policy / reset-stats endpoints, `EventRing`, ProblemDetail mapping
  - [x] Dashboard: Playground page (live), Overview "Removals by cause" chart, typed API client

## Gate 2 results
- Unit: LRU order / capacity 1 / replace-never-evicts; LFU tie-breaks / bucket deletion / TTL removal of last min-frequency node ✔
- Differential: 10,000 sequences × 200 ops for LRU and LFU equal the reference (15 s) ✔
- TTL (FakeTicker, parameterized LRU+LFU): expired read = miss + expiration, purge-before-evict, replace resets TTL, policy-independent expiry ✔
- Listener re-entry (listener calls `get`) completes; 100 threads `getOrLoad` → loader once ✔
- `./gradlew spotlessCheck build`: cache-core 117 tests, cache-server 43 tests, 0 failures ✔
- Dashboard: lint, typecheck, 169 tests, build, prettier ✔
- Playground (manual, browser): put with 5 s TTL → countdown 4.6 s … 0.5 s → entry disappears → get = MISS ✔; live policy switch ✔

## Known issues
- Port 8080 on this machine is held by an unrelated Apache `httpd`; run the server with `--server.port=8081`
  and the dashboard with `dashboard/.env.local` (`CACHELAB_API_URL=http://localhost:8081`, gitignored).
- Dashboard JS bundle ~600 KB (Recharts); Vite warns above 500 KB. Step 5 performance pass.
- `LFU_DECAY` and `concurrencyLevel > 1` are rejected until Step 3 delivers them.

## Next action
Step 3 (SPEC 12): `SegmentedCache`, `StressHarness`, `InvariantChecker`, `StampedeTest`, `LfuDecayPolicy`
(tests first for the decay merge); server `MetricsPublisher` on real stats (fake off), `LatencyRecorder`,
`CostModel` fields, events, `/api/stress`, `/api/stress/stampede`; workload key streams,
`SimulationService`, `SimulatedDatabase`, `/api/simulations`; dashboard Policy Race, Concurrency Lab,
cost panel.
