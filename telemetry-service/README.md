# CATCHY telemetry-service

Spring Boot 3.5 / Java 21 service of **CATCHY — AcentraCache Insight**. It receives *safe metadata only* from
AcentraCache SDK instances (key fingerprints, counters, events), aggregates it, runs the Policy Arena and serves the
engineer/admin dashboard API.

> **Healthcare rule:** raw cache keys, cached values, patient data, tokens and credentials are never collected, stored,
> logged or returned. Telemetry endpoints reject any property that is not part of the safe schema.

The API contract is [`../docs/api.md`](../docs/api.md). This service implements it as written; deviations and
interpretations are listed at the bottom.

## Run

```powershell
# from acentra-cache-insight/ (Maven is not required on PATH if you use your own mvn)
mvn -f telemetry-service/pom.xml spring-boot:run
```

With no configuration this starts on **http://localhost:8090** with the `local` profile: H2 in-memory (PostgreSQL mode),
Flyway migrations, demo projects/applications seeded, no demo users able to sign in (see below). Typical demo start:

```powershell
$env:CATCHY_DEMO_MODE = "true"                                     # one-click role login for the dashboard
$env:CATCHY_SEED_CLAIMS_API_KEY = "acc_c1a10001_7f3e9b2d4a6c81e0f5d7b3a9c2e4f601"   # registers this key (hashed) for claims-service
mvn -f telemetry-service/pom.xml spring-boot:run
```

Build and test: `mvn -f telemetry-service/pom.xml verify` (JDK 21+, the SDK must be installed:
`mvn -f acentra-cache-sdk/pom.xml install`).

### Profiles

| Profile | Database | Seeding default |
|---|---|---|
| `local` (default) | H2 in-memory, PostgreSQL compatibility mode | on |
| `postgres` (`SPRING_PROFILES_ACTIVE=postgres`) | PostgreSQL from `CATCHY_DB_*` | off |
| `test` | H2 in-memory (tests only) | off |

The schema is created by Flyway (`src/main/resources/db/migration`, portable SQL that runs on both databases);
Hibernate only validates it (`ddl-auto=validate`). Open-in-view is off.

### Environment variables

No secret has a default in code or in the YAML files.

| Variable | Default | Purpose |
|---|---|---|
| `SERVER_PORT` | `8090` | HTTP port |
| `SPRING_PROFILES_ACTIVE` | `local` | `local` or `postgres` |
| `CATCHY_DB_URL` | `jdbc:postgresql://localhost:5433/catchy` | PostgreSQL URL (`postgres` profile) |
| `CATCHY_DB_USER` | `catchy` | PostgreSQL user |
| `CATCHY_DB_PASSWORD` | empty | PostgreSQL password (no default) |
| `CATCHY_DEMO_MODE` | `false` | `true` enables `POST /api/v1/auth/demo-login`; otherwise it returns 404 |
| `CATCHY_TOKEN_SECRET` | empty | HMAC secret for session tokens. If blank a random secret is generated at startup (WARN logged, tokens reset on restart); the secret is never logged |
| `CATCHY_ADMIN_PASSWORD` / `CATCHY_ENGINEER_PASSWORD` / `CATCHY_VIEWER_PASSWORD` | empty | Passwords of the seeded users `admin` / `engineer` / `viewer` (stored BCrypt-hashed). A user with a blank password cannot use password login, only demo login |
| `CATCHY_DASHBOARD_ORIGIN` | `http://localhost:5173` | The only allowed CORS origin (comma separated for several) |
| `CATCHY_SEED_ENABLED` | `true` in `local`, else `false` | Idempotent demo data: projects *Claims Platform*, *Eligibility Platform*, *Prior Authorization Platform*; applications `claims-service` and `eligibility-service` (staging) |
| `CATCHY_SEED_CLAIMS_API_KEY` / `CATCHY_SEED_ELIGIBILITY_API_KEY` | empty | Key in the form `acc_<8 hex>_<32 hex>` registered (hashed) for that application. If unset, no key is created |
| `CATCHY_OLLAMA_ENABLED` | `false` | Optional AI explanation of Policy Arena decisions |
| `CATCHY_OLLAMA_URL` | `http://localhost:11434` | Ollama base URL |
| `CATCHY_OLLAMA_MODEL` | `llama3.2` | Ollama model |

Other tunables live under `catchy.*` in `application.yml`: ingestion limits (1 MB body, 500 events per batch, 600
requests per minute per key), advisor thresholds (`catchy.advisor.min-requests=100`, `min-improvement-percent=5.0`,
`cooldown=PT5M`, `interval=PT15S`), retention (20,000 events per application, 3 h of snapshot history), and
`catchy.metrics.instance-stale-after` (PT1H).

## Endpoints

Everything is under `/api/v1` and documented in [`../docs/api.md`](../docs/api.md) (shapes, enums, status codes).

| Area | Endpoints | Minimum role / auth |
|---|---|---|
| Health, auth | `GET /health`, `POST /auth/login`, `POST /auth/demo-login`, `GET /auth/me` | public / any role |
| Catalog | `GET` projects and applications; `POST /projects`, `POST /projects/{id}/applications` | VIEWER / ADMIN |
| API keys | `POST/GET /applications/{id}/api-keys`, `DELETE /api-keys/{id}` | ADMIN |
| Ingestion (SDK) | `POST /telemetry/events`, `POST /telemetry/events/batch`, `GET /telemetry/control` | `X-AcentraCache-Key` |
| Metrics | `/overview`, project / application / region metrics, health, events (Eviction X-ray), timelines | VIEWER |
| Region config | `GET .../regions/{r}/config`, `PUT` (ADMIN) | VIEWER / ADMIN |
| Policy Arena | `GET /applications/{id}/recommendations`, `POST .../recommendations/evaluate` | VIEWER / ENGINEER |
| Policy changes | create / list / approve / reject `policy-change-requests` | ENGINEER (approve/reject not your own, unless ADMIN) |
| Audit | `GET /audit-logs` | ADMIN |
| Simulation | `POST /applications/{id}/simulate/{sample-workload|high-load|ttl-expiration|policy-comparison}` | ENGINEER |

ADMIN includes ENGINEER includes VIEWER. Any mutating endpoint that is not listed explicitly defaults to ADMIN.

## How it works

* **Source of truth = snapshots.** The SDK sends cumulative per-region snapshots; the service keeps every snapshot
  (for timelines) and the latest one per *(application, region, instance)*. Displayed totals are the **sum across
  instances**; hit/miss/overall rates and memory utilization are derived from the summed counters (2 dp, hit + miss = 100),
  latencies are weighted by operation counts. Instances that stopped reporting for more than an hour before the region's
  newest instance are left out of the aggregate.
* **Restarts.** A counter that decreases between consecutive snapshots of the same instance is treated as a restart: the
  new value is the baseline, so timeline deltas are never negative. The first snapshot of a series only sets a baseline.
* **Health** comes from the SDK's own `CacheHealthEvaluator`; the reasons are returned verbatim, and alerts are derived
  from them (one per reason of every WARNING/CRITICAL region). `secondsSinceLastTelemetry` is computed server-side from
  the receipt time. An application's health is its worst region; UNKNOWN regions do not mask healthy ones.
* **Policy Arena.** Decisions are the SDK's deterministic `PolicyAdvisor` (minimum sample, minimum improvement,
  cooldown). Results are stored per region (stable id), refreshed every 15 s and on demand. The optional Ollama text is
  requested only from the evaluate endpoint and the background job, receives aggregate numbers only (no keys,
  fingerprints, values, application or region names), has a short timeout, and only ever fills `aiExplanation`.
* **Nothing changes automatically.** An approved policy request or an admin tuning override is only *offered* to the SDK
  through `GET /telemetry/control`; `APPLIED` / `CONFIG_CHANGE_APPLIED` are recorded when a later snapshot reports it.
* **Simulations** run synchronously inside the service with real SDK caches, synthetic keys and an in-process telemetry
  client that feeds ingestion directly (events and exact snapshots, no HTTP, no API key). Regions: `sim-sample-workload`,
  `sim-high-load`, `sim-ttl-expiration`, `sim-policy-lru`, `sim-policy-lfu`.
* **Retention** (scheduled): newest 20,000 events per application; snapshot history older than 3 hours is pruned (the
  latest snapshot of each instance is always kept).

## Security notes

* **Privacy guard.** Telemetry bodies are parsed strictly: any undeclared property (e.g. `rawKey`, `memberId`, `value`)
  returns 400 listing only the offending *field names* (sanitized and capped), writes a `TELEMETRY_REJECTED` audit row
  (names only) and stores nothing. Fingerprints must match `^sha256:[0-9a-f]{8,64}$`, `reason` is limited to 400 characters.
  Request bodies are never logged.
* **API keys** (`acc_<8 hex>_<32 hex>`): only the SHA-256 hash and a short prefix are stored, compared in constant time.
  The plaintext is returned once at creation and never logged. The key's application is authoritative (403 on a different
  `applicationName`/`environment`); revoked or unknown keys get 401; 600 requests/minute per key (429); 1 MB body limit (413).
* **Sessions** are stateless HMAC-SHA256 tokens (8 h). Dashboard tokens are not accepted on telemetry endpoints and API
  keys are not accepted anywhere else. Passwords are BCrypt-hashed and come only from the environment.
* **Audit log** covers logins, catalog changes, key create/revoke, policy and config workflow, evaluations, simulations,
  rejected telemetry and every denied action (`ACCESS_DENIED`). Details never contain keys, values, tokens or secrets.
* **Errors** always use the contract shape; 5xx responses say only `"Unexpected error"`. CORS allows only the dashboard origin.

## Contract notes (interpretations)

* `GET /overview` lists every registered application in `applications[]` (with `policyLabel` `NONE`, `No data yet`,
  `lastTelemetryAt: null` until telemetry arrives); the region/alert/recommendation lists are empty and totals zero.
* `tuningVersion` in a region config is `0` when the region was never overridden; `PUT` merges into the earlier desired
  values (only the supplied fields change) and increments the version each time.
* `GET /applications/{id}/recommendations` re-evaluates and stores the result only when it changed; the stored row keeps a
  stable `id`, `createdAt` is the time the current result was produced.
* Releasing an instance: `instanceCount` counts instances that are still part of the aggregate (see the stale rule above).
