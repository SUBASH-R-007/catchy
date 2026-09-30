# PROGRESS

**Current step:** Step 1 — Foundation and theme shell — **Gate 1 passed** (2026-09-30).
Waiting for `continue with Step 2`.

## Step 1 checklist

### Repo and build (M0)
- [x] Section 3 layout; placeholder modules compile (`cache-bench`, `cache-spring`, `formulary-service`)
- [x] Gradle 8.14 wrapper, Kotlin DSL, version catalog, Java 21 toolchain
- [x] Spotless (google-java-format) on all Java modules; JaCoCo report on `cache-core`
- [x] CI: Java job + dashboard job (`.github/workflows/ci.yml`)
- [x] `CLAUDE.md`, `PROGRESS.md`, `docs/DECISIONS.md`, ADR-001, ADR-002

### Library contracts (M0)
- [x] Public API types (4.1) with full Javadoc (doclint + `-Werror` enforced)
- [x] `CacheBuilder` validation (`build()` throws `UnsupportedOperationException` until Step 2)
- [x] `CacheStats` (rates, `minus`, `plus`)
- [x] `Node`, `FreqBucket` (minimal), `EvictionPolicy`
- [x] `FakeTicker` test fixture

### API documents
- [x] `docs/api/metrics.schema.json` (schema v2)
- [x] `docs/api/openapi.yaml` stub (all endpoints, each marked with its delivering step)

### Server
- [x] `GET /api/health`; `GET /api/metrics/stream` via `MetricsPublisher` (SSE lifecycle per 8.4)
- [x] Seeded `FakeMetricsSource` (`cachelab.metrics.fake=true`): group `demo`, `lru-A` dips every 30 s
- [x] ProblemDetail errors, dev-profile CORS, Swagger UI at `/swagger-ui`

### Dashboard (M8 part 1)
- [x] Theme tokens (blue/amber), self-hosted fonts, Tailwind mapping, type scale
- [x] Circuit background: seeded generator, 16 electron lanes, pause on hidden, reduced motion, toggle
- [x] All 10.3 components (+ `ErrorState`) with tests
- [x] Data layer: types, shape guard, reducer, connection with backoff + stale watchdog, `useMetricsStream`
- [x] Shell with 6 routes; Overview (KPI tiles + hit-rate chart with phase markers); stub pages

## Gate 1 results
- `./gradlew spotlessCheck build` ✔ (cache-core 46 tests, cache-server 30 tests, 0 failures)
- `:cache-core:dependencies --configuration runtimeClasspath` → "No dependencies" ✔
- `npm run lint && npm run typecheck && npm test -- --run && npm run build` ✔ (100 tests)
- `curl -N …/api/metrics/stream` → `event:metrics` every 500 ms, schema-valid ✔ (port 8081, see issues)
- Browser: electrons moving, LED green, tiles/chart updating, lru-A dip visible ✔
- Server stopped → LED amber (then red after 7 s) → restarted → green ✔
- Reduced motion: static board rule present (CSS inspection + unit test) ✔

## Known issues
- Port 8080 on this machine is held by an unrelated Apache `httpd` (PID 6720 at the time of checking). Run
  the server with `--server.port=8081`, and the dashboard with `dashboard/.env.local` containing
  `CACHELAB_API_URL=http://localhost:8081` (gitignored), or stop Apache.
- Dashboard JS bundle is 592 KB (183 KB gzip), mostly Recharts; Vite warns above 500 KB.
  Revisit in the Step 5 performance pass (code-split pages).
- `prefers-reduced-motion` could not be emulated in the browser pane; verified via CSS rule + tests.

## Next action
Step 2 — Core engine (SPEC 12): `IntrusiveList`, `LruPolicy`, `LfuPolicy` (tests first for the
bucket list), reference models + differential tests, then `BoundedCache`, `ExpiryIndex`, `Sweeper`,
`StatsRecorder`, listeners/observers, `getOrLoad`; server `CacheRegistry` + CRUD endpoints +
`EventRing`; dashboard Playground + remaining Overview charts.
