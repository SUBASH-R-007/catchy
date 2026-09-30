# CATCHY - AcentraCache Insight

**A reusable, thread-safe Java cache SDK plus a centralized observability platform for Acentra Health applications.**
Engineers drop the SDK into any Spring Boot (or plain Java) service to get in-memory caching with configurable LRU/LFU
eviction, per-entry TTL, memory limits and cache-region isolation, and watch cache health across services and environments on
a live dashboard, without any patient data leaving the application.

> This is a healthcare-aware engineering demonstration. It demonstrates privacy-conscious design but does not by itself
> establish legal or regulatory compliance certification.

## The problem

Acentra backends (claims, eligibility, prior authorization, care management, provider portal, rules engines) repeatedly read
the same non-sensitive reusable data from databases, APIs and rules repositories. Without a cache, that load slows responses;
with an *unobserved* cache, nobody knows whether it helps, wastes memory, expires too fast, evicts the wrong things, or serves
stale data in a place where that is unsafe. CATCHY answers:

* Which region has the best/worst hit rate? Which service uses the most (estimated) cache memory?
* Is LRU or LFU the better policy here, and should we switch? Which entries expire too quickly? Which region evicts too much?
* How many database/API calls are being avoided? Which application needs tuning? Is a configuration a stale-data risk?

## Who it is for

Acentra **backend engineers, platform engineers, application support engineers and authorized administrators.** End users of
Acentra products never see cache metrics; they benefit indirectly from faster, more stable services.

## Architecture

```
 claims-service (SDK) --+                                   +-- React dashboard (:5173)
                        |  safe telemetry, batched, async   |   REST + Bearer token
 eligibility-service ---+--------------> Telemetry Service (:8090) -----------> authorized engineer
        (SDK)                             API keys - privacy guard - aggregation
           ^                              health - Policy Arena - audit - simulations
           |                                          |
           +------ approved policy/tuning ------------+        PostgreSQL (or H2 locally)
```
Details: [docs/architecture.md](docs/architecture.md). Mermaid diagram included there.

```
acentra-cache-insight/
  acentra-cache-sdk/          Java 21 cache library (+75 tests)
  telemetry-service/          Spring Boot: ingestion, metrics, recommendations, audit, simulations
  demo-claims-service/        Spring Boot demo (claim-rules, provider-directory)
  demo-eligibility-service/   Spring Boot demo (eligibility-summary, authorization-decision)
  dashboard/                  React + Vite + TypeScript dashboard
  docs/  scripts/  postman/  docker/  docker-compose.yml  .env.example
```

## Features

* **SDK**: thread-safe get/put/remove/clear, per-entry TTL independent of eviction, **LRU** and **LFU** (LFU ties -> least
  recently used), entry-count *and* estimated-memory limits, runtime policy change, cache regions, hit/miss/eviction/expiration
  metrics, hit rate + miss rate = 100%, **Eviction X-ray** decision events, safe key fingerprints, batched async telemetry with
  bounded retry/backoff (cache stays fast when telemetry is down), deterministic health scoring with exact reasons.
* **Advanced**: **victim cache** (keeps original expiry), **Policy Arena** (shadow LRU vs shadow LFU, deterministic
  recommendation, never automatic), **Cache Stampede Shield** (single-flight, LOW-risk stale-while-revalidate, mandatory source
  validation for HIGH/CRITICAL), optional isolated **Ollama** explanation (off by default).
* **Telemetry service**: API-key auth (hashed keys), privacy guard, aggregated metrics by project/application/region, timeline,
  health, recommendations, approval workflow, admin tuning, audit log, role-based access (ADMIN/ENGINEER/VIEWER), simulations.
* **Dashboard**: overview KPIs, application cards, sortable region table (8 sort modes), hit/miss donut, memory bars, timelines,
  LRU-vs-LFU chart, eviction-reason chart, Eviction X-ray timeline, Policy Arena, recommendations, simulations, audit log,
  configuration, live polling every 3 s, light/dark theme, dev mock mode.

## Privacy-safe telemetry and healthcare-safe design

Only safe metadata leaves an application: region, action, latency, policy, TTL remaining, estimated size and a salted-SHA-256
**key fingerprint** (or nothing in category-only mode). Raw keys, raw values, request/response bodies, member IDs, claim IDs,
diagnosis data, tokens and credentials are never collected; the service rejects unknown fields outright. Risky regions cannot be
configured to serve stale data, and final decisions require source validation (see the `authorization-decision` demo).
**A cache can improve speed, but a cache must not make unsafe healthcare decisions based on stale data.**
Full model and limitations: [docs/security.md](docs/security.md).

## Prerequisites

* **JDK 21 or newer** (built with `--release 21`; tested on JDK 24) - no separate Maven install needed (`./mvnw` is included)
* **Node.js 20+** and npm (dashboard)
* **curl** (demo commands). **Docker** is optional (PostgreSQL / full stack).

## Step-by-step setup

### Fastest: one command

```bash
# Linux / macOS / Git Bash
scripts/start-all.sh
# Windows (cmd or PowerShell)
scripts\start-all.bat
```
It creates `.env` from `.env.example` (dev placeholders), builds everything, starts telemetry-service (:8090), both demo
services (:8091, :8092) and the dashboard (:5173), and prints the URLs. Stop with `scripts/stop-all.sh` / `scripts\stop-all.bat`.

### Manual steps

1. `cp .env.example .env` (Windows: `copy .env.example .env`) and export it, or pass the variables you need.
2. **Database.** Default is an in-memory H2 database - nothing to install. For PostgreSQL:
   ```bash
   docker compose up -d postgres             # host port 5433 (avoids a PostgreSQL already on 5432)
   export SPRING_PROFILES_ACTIVE=postgres    # PowerShell: $env:SPRING_PROFILES_ACTIVE="postgres"
   ```
3. **Telemetry service** (port 8090):
   ```bash
   ./mvnw -pl telemetry-service -am spring-boot:run       # Windows: mvnw.cmd
   ```
4. **Demo services** (new terminals):
   ```bash
   ./mvnw -pl demo-claims-service -am spring-boot:run       # :8091
   ./mvnw -pl demo-eligibility-service -am spring-boot:run  # :8092
   ```
   They send telemetry when `CATCHY_CLAIMS_API_KEY` / `CATCHY_ELIGIBILITY_API_KEY` are set (the `.env.example` values match the
   keys the telemetry service seeds in demo mode). Without a key they run normally with telemetry disabled.
5. **Dashboard** (port 5173):
   ```bash
   cd dashboard && npm install && npm run dev
   ```
   No backend? `npm run dev:mock` runs the UI against an in-browser mock.
6. Open **http://localhost:5173** and choose a role (demo mode) or log in with `admin` / `engineer` / `viewer` and the passwords
   from your `.env`.

Build and test everything: `./mvnw verify` (188 Java tests: SDK 75, telemetry-service 83, claims 14, eligibility 16),
`cd dashboard && npm test -- --run && npm run build` (172 tests). With the stack running you can also validate the real API against the
dashboard's contract: `cd dashboard && CATCHY_LIVE_API=http://localhost:8090 npm test -- --run src/test/liveContract.test.ts`
(skipped unless that variable is set).
Full-stack containers (untested in this repo's CI): `docker compose --profile full up --build`.

### Ports

| Service | Port | | Service | Port |
|---|---|---|---|---|
| telemetry-service | 8090 | | dashboard | 5173 |
| demo-claims-service | 8091 | | PostgreSQL (docker) | 5433 |
| demo-eligibility-service | 8092 | | | |

Port already taken (e.g. another Vite app on 5173)? Override without editing `.env`:
`CATCHY_DASHBOARD_PORT=5180 scripts/start-all.sh` (Windows: `set CATCHY_DASHBOARD_PORT=5180` first). The script starts the dashboard
with `--strictPort` and tells you if the port is busy instead of silently moving to another one.

## Trigger workloads and inspect the dashboard

```bash
scripts/demo-load.sh                       # all workloads + simulations, a few rounds
# or one at a time:
curl -X POST localhost:8091/api/demo/claims/workload/repeated -H 'Content-Type: application/json' -d '{"requests":400}'
curl -X POST localhost:8091/api/demo/claims/workload/changing -H 'Content-Type: application/json' -d '{"requests":400}'
curl -X POST localhost:8091/api/demo/claims/workload/expire   -H 'Content-Type: application/json' -d '{"requests":60}'
curl -X POST localhost:8092/api/demo/eligibility/workload/repeated   -H 'Content-Type: application/json' -d '{"requests":400}'
curl -X POST localhost:8092/api/demo/eligibility/workload/high-churn -H 'Content-Type: application/json' -d '{"requests":800}'
curl localhost:8091/api/demo/claims/cache        # local view of the SDK metrics/health/recommendation
```
Within ~3 s the dashboard shows the change: **Overview** (KPIs, alerts), an **application card**, then a **region** page
(health reasons, charts, Eviction X-ray). Sort the region table by *Lowest hit rate* or *Most evictions* to find problems.

### Test LRU versus LFU

* Dashboard: **Simulations -> Policy comparison** replays a changing-access stream (LRU wins) and a stable-popularity stream
  (LFU wins) on real SDK caches and shows both hit rates. **Policy Arena** shows the live shadow LRU/LFU rates per region.
* Live: `workload/repeated` favours LFU, `workload/changing` favours LRU; request a switch (ENGINEER), approve it (ADMIN), and watch
  `POLICY_CHANGED` in the X-ray and the active policy change.
* Code: `AcentraCacheTest` (capacity pattern D, LFU tie-break), `AccessPatternSimulatorTest` (patterns A/B).

### Demonstrate TTL expiry

`workload/expire` (claims) or **Simulations -> TTL expiration** puts a key with a 500 ms TTL, waits 600 ms, reads it: a **MISS**
and an **EXPIRED** event are recorded and the expirations chart rises. An expired entry is never returned, even if it is the most frequent.

### Demonstrate memory-limit eviction

`workload/high-churn` (eligibility) or **Simulations -> High load** overflows the small estimated-memory limit:
`MEMORY_EVICTED` events, "Memory limit" bar in the eviction-reason chart, utilization near 100% and a WARNING with reasons.

### Demonstrate cache stampede protection

`ConcurrencyTest#stampedeShieldCoalescesFiftyConcurrentLoadsIntoOne` expires one key and fires 50 concurrent `getOrLoad`s: exactly
**one** source call; `concurrentRequestsCoalesced` = 49. On the live service:
`curl -X POST localhost:8091/api/demo/claims/workload/stampede` fires concurrent callers at one uncached key; the summary reports
how many source calls were made versus avoided, and `curl localhost:8091/api/demo/claims/cache` shows
`concurrentRequestsCoalesced` / `sourceCalls` for the region.

### Demonstrate source validation for critical data

```bash
curl -X POST localhost:8092/api/demo/eligibility/authorization-source/SYN-000777/change   # upstream decision changes
curl localhost:8092/api/authorization/SYN-000777/preliminary       # cache allowed: "preliminary": true, "finalDecisionAllowed": false
curl -X POST localhost:8092/api/authorization/SYN-000777/finalize   # always asks the source: "validatedAgainstSource": true
curl -X POST localhost:8092/api/eligibility/SYN-000123/validate     # drift detection on eligibility-summary
```

## Using the SDK in your own service

```java
AcentraCacheManager manager = AcentraCacheManager.builder()
    .applicationName("claims-service").environment("staging")
    .keySalt(System.getenv("CATCHY_KEY_SALT"))                 // fingerprint salt: keep out of source control
    .telemetryEndpoint("http://telemetry:8090").telemetryApiKey(System.getenv("CATCHY_CLAIMS_API_KEY"))
    .build();

AcentraCache<String, RuleBundle> rules = manager.cache(CacheRegionConfig.builder()
    .regionName("claim-rules").maximumEntries(10_000).maximumMemoryBytes(256L * 1024 * 1024)
    .defaultPolicy(EvictionPolicy.LFU).defaultTtl(Duration.ofMinutes(15)).riskLevel(CacheRiskLevel.MEDIUM));

RuleBundle b = rules.getOrLoad("rules:" + id, key -> ruleRepository.load(id));   // single-flight per key
Optional<RuleBundle> maybe = rules.get("rules:" + id);                           // valid hit or empty
rules.put("rules:" + id, bundle, Duration.ofHours(24));                          // per-entry TTL
```

## API documentation

* [docs/api.md](docs/api.md) - every endpoint, shape, role and status code (the contract)
* [postman/acentra-cache-insight.postman_collection.json](postman/acentra-cache-insight.postman_collection.json)
* Other docs: [architecture](docs/architecture.md), [security](docs/security.md), [demo script](docs/demo-script.md)

## Known limitations

* **In-process, per-instance cache**: not distributed; no cross-instance invalidation.
* **Estimated memory**, not exact JVM heap accounting; accuracy depends on the estimator.
* The shadow (Policy Arena) simulation models entry-count capacity (memory-adjusted by average entry size) and read-through with
  the region TTL; it is an approximation of the real workload, not a measurement.
* Demo authentication and rate limiting are intentionally simple; no TLS is included; see [docs/security.md](docs/security.md).
* Routine HIT/PUT events are sampled (snapshots stay exact); local decision logs keep only the newest 500 events per region.
* The Docker Compose "full" profile and PostgreSQL profile were not exercised in the environment this was built in (Docker
  was unavailable); the H2 local profile is what was verified end to end.
* Not a compliance certification; no PHI handling claims beyond the design described.

## Future enhancements

Distributed/invalidation-aware caches (Redis tier, pub/sub invalidation), striped locking for extreme contention, W-TinyLFU,
Spring Boot starter and Micrometer export, OIDC/SSO and per-project RBAC, Helm chart + TLS, alert routing (PagerDuty/Teams),
retention policies and downsampled long-term metrics, trace replay with Belady's optimal upper bound.
