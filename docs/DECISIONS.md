# Decisions not fixed by the spec

One line each: the decision, then the reason. Newest at the bottom of each step.

## Step 1 — build and tooling

- Gradle wrapper pinned to 8.14 (not the newest 8.14.x) — it was already cached locally and the network is ~200 KB/s; any 8.x satisfies SPEC 2.
- Java 21 via a Gradle toolchain with the foojay resolver — builds target 21 exactly even when the Gradle daemon runs on a newer JDK (this machine has JDK 23).
- Spring Boot 3.5.16 and springdoc 2.8.17 — latest 3.x release, and the springdoc line built for Boot 3.
- JUnit 5.14.4 in non-Spring modules; Spring modules use Boot's managed JUnit — keeps one JUnit per classpath with no version fights.
- The example app's Gradle path is `:formulary-service` (directory `examples/formulary-service`) — avoids an empty `:examples` parent project.
- `cache-core` compiles with `-Xlint:all -Werror`, and javadoc runs with doclint `all` plus `-Werror` — this enforces "Javadoc on every public type and method" in the build.
- JaCoCo 0.8.14 produces a report on every `cache-core` test run; no minimum coverage is enforced yet — Step 1 has only contract code, so a threshold is revisited when the engine lands.
- A `.gitattributes` with `eol=lf` — Windows `core.autocrlf=true` would otherwise fight Spotless and Prettier.
- Test fixtures live in package `io.cachelab.testing` (`FakeTicker`, later the reference models) — the spec names the fixtures but not their package.

## Step 1 — library contracts

- `maximumSize` is required: `build()` throws `IllegalStateException` if it is unset — the spec gives no default, and a silent default would hide misconfiguration.
- Cross-field rule `concurrencyLevel <= maximumSize` is checked in `build()`, not in the setters — so setter order does not matter. Setters are last-wins, except `accessObserver`, which accumulates (as the spec says).
- Builder usage is `CacheBuilder.<K, V>newBuilder()` with an explicit type witness — type-safe with no unchecked casts (unlike Caffeine's `build()` generics trick).
- Validation messages print durations as `"9 ms"`/`"-5 ns"`, falling back to ISO-8601 when out of nanosecond range — this keeps them readable and overflow-safe.
- `Node.isExpired` also checks the `NEVER` sentinel explicitly — the spec's bare `now - expiresAt >= 0` is wrong for never-expiring nodes when ticker readings are negative (`System.nanoTime()` may be).
- `Node` and `EvictionPolicy` are `public`, in `io.cachelab.internal`; Node's fields stay package-private — the advisor's `ShadowCache` (a different package, SPEC 6.3) must create nodes and drive policies.
- `FreqBucket` has only `freq` and its links in Step 1; its member `IntrusiveList` arrives with `LfuPolicy` in Step 2 — `Node` needs the type to compile now.
- `CacheStats.hitRate()` and `missRate()` are both 0.0 when there are no requests (Caffeine reports a hit rate of 1.0) — this follows SPEC 4.7 literally.
- `CacheStats.minus()` clamps at 0; `plus()` (saturating) was added — deltas never go negative after a baseline reset, and segments need summing (SPEC 4.8).
- `remove(k)` on an entry that has already expired fires `EXPIRED` and returns `false` — so an expired entry is never reported as an explicit removal of a live value.
- `getOrLoad` treats a loader returning null as a failure (`CacheLoadException`, not cached) — null values are rejected everywhere else.

## Step 1 — server (fake metrics stream)

- The fake generator advances by tick index (tick i covers i × 500 ms), never by wall clock; only `ts` and `phaseStartedTs` read the injected `Clock` — this makes it deterministic per seed and testable.
- In the fake stream, `lru-A`'s per-tick hit rate is ≈0.35 during seconds 20–25 of each 30 s cycle, then climbs back from 0.58 towards ≈0.78. The 10 s window plotted on the chart therefore bottoms out near 0.55: five dip seconds are averaged with five normal ones.
- The fake stream carries a looping 3-phase `simulation` whose captions start with "Fake stream (dev only)". This lets phase markers be built now and makes it obvious that the data is fake.
- `phaseStartedTs` is fixed when a phase begins — recomputing it each tick made it jitter by a few ms.
- `getP50Micros`/`getP99Micros` names are pinned with `@JsonProperty` — Jackson would otherwise treat `get…` as a bean getter and write `p50Micros`.
- `MetricsPublisher` uses a small lock so a new client's first payload and the next tick are never duplicated or reordered. The latest payload is sent on connect, so a client sees data at once.
- `MetricsPublisher` is a `SmartLifecycle` that completes open emitters before Tomcat's graceful shutdown — open SSE connections never hold up shutdown.
- The `MetricsSource` is chosen by a `@Bean` method from bound properties, not `@ConditionalOnProperty` — so the record default and the YAML cannot disagree.
- `Pattern` (`workload` package), `CostModel` and `HitRateWindow` are separate classes now — the real publisher (Step 3) reuses them. `HitRateWindow` rejects negative deltas, so a stats reset must also clear the window.
- Schema conformance is tested with a ~150-line validator in test code, covering exactly the keywords the schema uses — no JSON-schema library dependency (SPEC 0 rule 7).

## Step 1 — dashboard

- React 18.3 with React Router 7 — Router 8 requires React 19, and the spec fixes React 18.
- Vite 7 + Vitest 4 + jsdom 27 — they run on Node 20.19+ (the Docker build stage) and on this machine's Node 22.17 (jsdom 30 needs Node 22.22+).
- TypeScript 5.9 and ESLint 9 — typescript-eslint supports TypeScript below 6.1, and eslint-plugin-jsx-a11y supports up to ESLint 9.
- eslint-plugin-jsx-a11y is a dev dependency — it enforces the accessibility rules of SPEC 10.8 in lint.
- Tailwind v4, with theme colours mapped through `@theme inline` to the SPEC 10.1 CSS variables — both themes switch through `[data-theme]` alone.
- The type scale is enforced by redefining Tailwind's text sizes to 14/16/20/28 px only — other sizes simply do not exist.
- The dev server proxies `/api` to `:8080`, and a `dev`-profile CORS rule for `:5173` also exists (SPEC 8.1) — the proxy keeps the SSE same-origin, and CORS allows direct calls.
- The proxy target can be overridden with `CACHELAB_API_URL` (e.g. `http://localhost:8081`) — on this machine port 8080 is held by an unrelated Apache `httpd`.
- The stream LED shows amber "Reconnecting" for the first 3 failures (backoff 1, 2, 4 s), then red "Offline" while retrying every 8 s — this distinguishes a blip from a server that is down.
- The browser's EventSource auto-retry is bypassed (close on error, reopen ourselves) — SPEC 10.4 requires our own 1/2/4/8 s backoff.
- Snapshots are buffered and flushed into React state every 500 ms — this caps re-renders at twice per second (SPEC 10.4, 10.8).
- The KPI "hit rate"/"miss rate" tiles show the 10 s window, with the all-time rate as a sublabel — the cumulative rate barely moves, so the window is what shows live behaviour.
- Phase-marker captions are fitted to the room before the next marker (labels alternate between two rows); full captions are in each marker's tooltip and in the chart's text summary — long captions would otherwise overlap.
- Chart end labels are HTML with collision avoidance (minimum 22 px apart), in text colour next to a line-style glyph — the labels never overlap, and identity is never carried by colour alone.
- A stale-stream watchdog treats an open stream with no event for 3 s (6 missed ticks) as failed and reconnects with backoff — in testing, Vite's proxy kept the browser's SSE response open after the server died, which left the LED stuck on "Live". The dev proxy now also ends the response on upstream errors.
- The Size KPI tile shows the entry count, with "of <capacity> · <n>% full" as its sublabel — "1K / 1K" wrapped in the 7-column tile row.
- Only `<html>` paints the board colour; `body` is transparent — an opaque body background covers the fixed `z-index: -1` circuit layer.
- The electron keyframes are named `circuit-flow` (the spec says `flow`), and all electron rules are scoped under `.circuit-bg` — this avoids global name clashes (e.g. with the Step 4 data bus). The behaviour is exactly SPEC 10.2's.
- Circuit generator: traces never cross, and ICs have a one-cell keep-out. Vias sit on ~35% of bends (at most 2 per trace), and pads only at ends inside the board. The 16 lanes are the longest traces (at most 2 per bus), with lap time growing with length within 7–12 s. It takes the first of up to 60 seeded layouts that meets the targets, so output stays deterministic. `--check` ignores CRLF/LF differences.
- PolicyBadge is a small pill on the page background — LFU_DECAY green on `surface-2` is only ~4.4:1, below AA.
- LedRow shows a visible "PASS"/"FAIL" word next to each LED, and a failed LED's detail tooltip is keyboard-focusable and dismissable with Esc — status is never colour-only (WCAG 1.4.1, 1.4.13).
- InfoPopover stays in the DOM with the `hidden` attribute, so `aria-controls` always resolves. It flips alignment near the viewport edge and closes on focus-out.
- Reduced motion is checked by CSS-rule inspection in the live app plus unit tests — the browser pane cannot emulate `prefers-reduced-motion`.
- Palette validator (dataviz skill): colour-blind separation, the normal-vision floor and contrast (≥ 3:1) pass. The "lightness band" and "chroma" checks flag the spec-fixed Okabe-Ito colours on this dark surface, and flag Optimal's deliberately achromatic grey. These colours are kept per SPEC 10.1; every series also has a distinct line style and a direct label.

## Step 2 — core engine

- `getOrLoad` re-checks the cache after winning the single-flight race — this closes the window where another load finished between our miss and our registration (no double load).
- Loader `Error`s, not just exceptions, complete the in-flight future exceptionally — waiters never hang. Each waiter gets a fresh `CacheLoadException` with the original message and cause.
- The access tick is an `AtomicLong` handed to the engine — segments of a `SegmentedCache` can share it (SPEC 4.8).
- `LruPolicy.rebuildFrom` leaves node frequencies untouched (LRU ignores them), so LFU → LRU → LFU keeps them; LRU snapshots report frequency 0.
- `entries(limit)` skips expired-but-unswept entries, widening its policy snapshot as needed — the listing never shows a dead entry (worst case O(n)).
- `CacheSettings` (an internal record) carries the validated builder config to the engine — the builder stays in the public package, and the engine never sees a half-built builder.
- The TTL time source is a Spring `Ticker` bean, replaced by `FakeTicker` in API tests — TTL endpoint tests are deterministic with no sleeps.
- `switchPolicy` (SPEC 6.2) and `POST /api/caches/{name}/policy` ship in Step 2, although the spec lists them in Step 3 — the Step 2 Playground needs its live DIP switch. Tests ship with them.
- Until Step 3, `LFU_DECAY` and `concurrencyLevel > 1` are rejected with a clear 400 / `UnsupportedOperationException` — they are delivered with `LfuDecayPolicy` and `SegmentedCache`.
- `EventRing` numbers every event — the Step 3 publisher can take exactly "the events since the last tick" without duplicates.
- The cache API always returns every field (null when absent), e.g. `GetResult{hit, value, ttlRemainingMs}` — simpler clients than optional keys.
- Cache and group names are 1–40 `[A-Za-z0-9_-]`, and keys at most 200 characters without `/` — names are path segments, and Tomcat rejects encoded slashes.
- The library's `IllegalArgumentException`/`IllegalStateException`/`UnsupportedOperationException` map to 400 with the library's own message — its validation messages are written for humans.
- Removals chart: evictions use solid `--trace-glow` bars, expirations hatched `--pad` bars, and the legend says "(solid)"/"(hatched)". Each theme is essentially one hue, so no two theme tokens pass the palette validator by colour alone; the texture and labels are the second cue.
- Playground defaults: name `play-N` (auto-incremented), capacity 5 so evictions are visible, and new caches go in group `playground`. It polls `/entries` every 1 s and `/api/caches` every 2 s, only while the tab is visible. TTL countdowns interpolate every 100 ms between polls.
- Playground polling errors show an inline "Lost contact… retrying" when stale data exists — toasts are reserved for user actions, so a dead server doesn't spam one per second.

## Step 3 — integration MVP

- `SegmentedCache` gives the first `maximumSize % N` segments one extra slot, so capacity is exact (SPEC 4.8 prefers exact).
- Segment snapshots merge through a per-entry access tick shared by all segments (`BoundedCache.Ranked`): LRU is ordered by tick; LFU by frequency, then tick.
- Stress workers call `nanoTime()` once per 64 operations — the deadline check doesn't dominate the measured throughput.
- The stress gate (20 runs × 32 threads × 5 s per engine, about 3.5 min) is a JUnit `@Tag("stress")` test run by `./gradlew :cache-core:stressTest`, not part of `build` — the gate runs it explicitly, and CI stays fast.
- `StressService` runs stress and stampede jobs one at a time; a busy request gets a 409 from a controller-local handler — concurrent CPU-heavy runs would distort each other's numbers.
- LFU-decay merge relinks nodes in place (`IntrusiveList.mergeByRecency`), with no per-node allocation. Measured pauses: 0.72 ms at 10k and 3.99 ms at 100k entries (ADR-004).
- `ShadowCache` decays itself on its own clock and catches up on missed intervals (at most 64 halvings) — shadows see bursty traffic, unlike the engine's 100 ms sweeper.
- Key streams run on logical time `elapsedMs = phaseOpIndex × 1000 / effectiveRate` (the rate is 5,000 when unthrottled) — time-based patterns are deterministic per op index. Live runs switch phases on wall-clock time; `runOps` switches on logical time.
- All per-op randomness comes from one `SplittableRandom(seed)` in a fixed order (key, read/write, TTL decision); each cache's `SimulatedDatabase` has its own seeded stream — every cache sees an identical key sequence.
- SCAN_POLLUTION puts cold keys on even op indices, cycling `cold:0..4999`, and Zipf hot keys on odd ones — the spec's 1:1 interleave, for the whole phase. Every policy therefore loses the cold half; LFU keeps its hot set (hot-traffic hit rate about 70 % vs LRU about 55 %).
- LOOP re-reads a random loop key 2 % of the time (`rereadShare`, 0 = strict loop) — on a strict cold loop every LFU frequency stays 1 and LFU scores 0 % like LRU. Measured over 200k ops: LRU 3.6 %, LFU 71.7 %.
- FORMULARY surges during the last 10 s of every 40 s cycle, onto 200 evenly spaced common drugs `drug:(i×250+125)`. PROVIDER_DIRECTORY sends 70 % of traffic to a hot region (`prov:r<0-9>:<n>`) that moves every 30 s.
- Unknown or out-of-range simulation `params` are a 400 listing the allowed names — a typo must not silently fall back to defaults.
- A new simulation's parameters are validated *before* the running one is stopped — a bad request never kills a live demo. The last 20 run summaries are kept for Step 4 reports.
- `LatencyRecorder`: 20 log-scale buckets per decade from 0.1 µs to 100 ms; it reports bucket geometric midpoints (±6 %) over a ring of 20 half-second slices rotated by the metrics tick.
- Real metrics track per-`ManagedCache` state, so a recreated cache starts fresh. A counter decrease means a stats reset: the 10 s window is cleared and the post-reset counts become that tick's delta.
- `cachelab.metrics.fake` now defaults to `false`, both in `application.yml` and in the properties record (SPEC 8.3).
- Policy Race: Start stays enabled while running and reads "Switch to <pattern>" (the server replaces the running simulation). The selected group lives in `?group=` so the guided demo can drive the page.
- Event log: millisecond timestamps (removals often share a second); EXPLICIT shows as "Deleted"; each cause has an icon and a word, so it never relies on colour alone.
- Concurrency Lab: `useStressTest()`/`useStampedeTest()` hooks expose `run(config)` for the Step 4 guided demo. A 409 shows a toast only; other errors add an ErrorState with Retry.
- Cost panel: an editable price per 1,000 calls, persisted in localStorage (`cachelab.pricePer1000`) behind try/catch. Values are labelled "Estimate", with an info popover listing the assumptions.
