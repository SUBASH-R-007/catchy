# PROGRESS

**Current step:** Step 3 — Integration and MVP — **Gate 3 passed** (2026-09-30). Building Steps 2–5
and pushing each to `origin/main` (user request); continuing with Step 4.

## Done
- **Step 1** — foundation and theme shell (Gate 1).
- **Step 2** — core engine, cache API, Playground (Gate 2).
- **Step 3** — integration MVP:
  - [x] `SegmentedCache` (exact capacity split, shared tick, merged snapshots), `LfuDecayPolicy` (linear merge), `switchPolicy`
  - [x] `StressHarness`, `InvariantChecker` (5 invariants), `StampedeTest`, `stressTest` Gradle task
  - [x] Server: real `MetricsPublisher` source (deltas, 10 s window, `LatencyRecorder`, cost fields, events), fake off by default
  - [x] Workload lab: 8 key streams, `SimulatedDatabase`, `SimulationService` (plans/phases), `/api/simulations`
  - [x] `/api/stress`, `/api/stress/stampede` (one job at a time, 409)
  - [x] Dashboard: Policy Race (without advisor/optimal), Concurrency Lab (stress, LedRow, stampede), cost panel

## Gate 3 results
- Stress: 32 threads × 5 s, 20 consecutive runs per engine — 40/40 passed all 5 invariants, deadlock-free ✔
- Determinism: 2 × 50,000 ops unthrottled, identical hits/misses for all 7 non-TTL patterns ✔
- LFU decay: unit tests + pause 0.72 ms @ 10k, 3.99 ms @ 100k; eviction matches reference with decay ✔
- Policy switch tests ✔; SSE emitter lifecycle test ✔
- `./gradlew spotlessCheck build` green (cache-core 139+ tests, cache-server 103 tests) ✔; dashboard 226 tests ✔
- Live (browser, port 8081): ZIPF (LFU 74 %, LRU 68 %) → SCAN_POLLUTION (LFU 37 %, LRU 27 %): LRU drops further,
  LFU keeps its hot set; event log fills (LRU evicting hot keys, LFU evicting cold); stress: five green LEDs for
  single lock (9.7 M ops) and segmented (22.1 M ops, 4.4 M ops/s); stampede: 200 threads → loader ran 1 time ✔

## Known issues
- Port 8080 is held by an unrelated Apache `httpd` on this machine: use `--server.port=8081` and
  `dashboard/.env.local` (`CACHELAB_API_URL=http://localhost:8081`, gitignored).
- Dashboard bundle ~670 KB (Recharts); Step 5 performance pass.
- SCAN_POLLUTION's 1:1 cold interleave lowers every policy's hit rate (cold keys never hit); LFU's advantage
  is that it keeps its hot set — explained in captions.

## Next action
Step 4 (SPEC 12): advisor/optimal (core started: `ShadowCache`, `PolicyAdvisor`, `KeyRecorder`,
`OptimalReplay` with tests), group fields in SSE + `/advisor/apply`; run JMH + export; `cache-spring`
(started) + `formulary-service` with `LatencyComparisonTest`; trace replay + sample CSV; reports;
`DemoActService`; dashboard advisor banner, optimal line, "Inside the cache", bench chart, Trace Replay,
Integrations, Data bus, guided demo.
