# demo-eligibility-service

Synthetic **eligibility-verification** application for CATCHY (AcentraCache Insight). It shows how a **sensitive** workload
uses the AcentraCache SDK safely: minimal derived results only, hashed cache keys, short TTLs, no stale serving, and
mandatory source validation for final actions. It sends **safe telemetry only** (key fingerprints, counters, events).
**All data is synthetic; member ids look like `SYN-000123`. There is no patient data anywhere.**

* Application name in the dashboard: `eligibility-service` - port **8092**
* Java 21, Spring Boot 3.5.16, depends on `com.acentra.cache:acentra-cache-sdk:1.0.0-SNAPSHOT` (install it first:
  `mvn -f acentra-cache-sdk/pom.xml install`)

## Run

```bash
mvn -f demo-eligibility-service/pom.xml test                # tests (no network; telemetry is captured in-process)
mvn -f demo-eligibility-service/pom.xml package -DskipTests
java -jar demo-eligibility-service/target/demo-eligibility-service-1.0.0-SNAPSHOT.jar
```

With no API key the service still works fully; it just does not send telemetry (one INFO line says so).
To connect it to the telemetry service and the dashboard:

```bash
export CATCHY_TELEMETRY_URL=http://localhost:8090
export CATCHY_ELIGIBILITY_API_KEY=<API key created for eligibility-service in the dashboard / seeded dev key>
java -jar demo-eligibility-service/target/demo-eligibility-service-1.0.0-SNAPSHOT.jar
```

PowerShell: `$env:CATCHY_ELIGIBILITY_API_KEY = "..."; java -jar ...`

## Environment variables (no secrets in code or in `application.yml`)

| Variable | Default | Meaning |
|---|---|---|
| `CATCHY_TELEMETRY_URL` | `http://localhost:8090` | Telemetry service base URL |
| `CATCHY_ELIGIBILITY_API_KEY` | *(empty)* | Application API key. Blank or missing means telemetry is **disabled** |
| `CATCHY_KEY_SALT` | *(empty)* | Salt for the telemetry fingerprints **and** the hashed cache keys. Blank means a random per-process salt |
| `CATCHY_ENVIRONMENT` | `staging` | Environment label sent with telemetry |
| `SERVER_PORT` | `8092` | HTTP port (instance id is `eligibility-service-<port>`) |
| `CATCHY_DASHBOARD_URL` | `http://localhost:5173` | Only used for the hint text in workload summaries |

The SDK snapshots and flushes every 2 s, so the dashboard updates almost live.

## Cache regions

| Region | Policy | Risk | TTL | Capacity | Notes |
|---|---|---|---|---|---|
| `eligibility-summary` | LRU | **HIGH** | 2 min | 150 entries, 512 KB | minimal derived result `{active, planTier, asOf}` only. Keys are a salted SHA-256 of `eligibility:member:<id>`. **No stale-while-revalidate** (the SDK rejects it for HIGH risk) |
| `authorization-decision` | LRU | **CRITICAL** | 60 s | 50 entries, 256 KB | status + date only. Preliminary display only; a final action always revalidates against the source |

**Memory accounting.** A cached result is tiny (~350 bytes by the SDK's standard estimate), which would never reach a 512 KB
limit with 150 entries. `FixedFootprintEstimator` (the SDK's `CacheMemoryEstimator` extension point) therefore charges each
`eligibility-summary` entry a conservative 4 KB, modeling the real object-graph/envelope cost. The memory limit then binds
at 128 entries, before the 150-entry limit, so churn produces **memory-limit evictions**. Figures are estimates, not exact JVM heap.

`SimulatedSource` fakes the eligibility/authorization system of record: every call sleeps 5-15 ms, is counted, and returns
deterministic data derived from the synthetic id, with a mutable override so the demo can simulate an upstream change.

## Endpoints

```bash
curl localhost:8092/api/health
curl localhost:8092/api/eligibility/SYN-000123                       # servedFrom: SOURCE, then CACHE on repeat
curl localhost:8092/api/demo/eligibility/cache                       # local view: per-region metrics + health + recommendation
curl -X POST localhost:8092/api/demo/eligibility/cache/clear
```

Member and request ids must match `^SYN-[0-9]{1,9}$`, otherwise `400` (the rejected value is never echoed or logged).

### Source validation and drift (the teaching flow)

```bash
curl    localhost:8092/api/eligibility/SYN-000123                              # cached: active=true
curl -X POST localhost:8092/api/demo/eligibility/source/SYN-000123/change      # upstream change (flips the source status)
curl    localhost:8092/api/eligibility/SYN-000123                              # still CACHE and now stale (this is the risk)
curl -X POST localhost:8092/api/eligibility/SYN-000123/validate                # requireFreshFromSource
# => {"cachedBefore":{...},"sourceValue":{...},"driftDetected":true,"cacheCorrected":true,...}
```

### Authorization: preliminary vs final

```bash
curl    localhost:8092/api/authorization/SYN-000777/preliminary   # cache allowed; "preliminary": true, "finalDecisionAllowed": false
curl -X POST localhost:8092/api/authorization/SYN-000777/finalize # ALWAYS hits the source; "preliminary": false, "validatedAgainstSource": true
curl -X POST localhost:8092/api/demo/eligibility/authorization-source/SYN-000777/change   # optional: make finalize report drift
```

### Synthetic workloads (synchronous, ≤ ~10 s)

Optional body `{ "requests": 500 }` (10-10000). Both also run a few authorization flows (2 preliminary reads + 1 finalize each).

```bash
curl -X POST localhost:8092/api/demo/eligibility/workload/repeated                       # default 500: few members queried repeatedly => high hit rate
curl -X POST localhost:8092/api/demo/eligibility/workload/high-churn                     # default 2000 unique members => evictions, memory-limit evictions, expirations
curl -X POST localhost:8092/api/demo/eligibility/workload/high-churn -H 'Content-Type: application/json' -d '{"requests": 5000}'
```

`high-churn` gives every 5th entry a 1 s TTL and waits ~1.2 s before measuring so expirations show up in the summary.
Summaries contain `requests`, `cacheHits`/`cacheMisses`, `sourceCallsMade`, `sourceCallsAvoided`, `evictions`,
`expirations`, a per-region breakdown (active policy, entry/memory-limit evictions, size/capacity) and a hint. Numbers are
deltas of the SDK metrics taken around the run. They contain no member ids.

## Connecting to the dashboard

Start the telemetry service (port 8090) and the dashboard (`http://localhost:5173`), set `CATCHY_ELIGIBILITY_API_KEY`, start
this service, then run the workloads. `eligibility-service` appears with its two regions; `high-churn` drives memory
utilization to ~100% and produces `MEMORY_EVICTED` events, and the health score explains why. Policy changes are never applied
automatically: an engineer approves them in the dashboard and the SDK then applies them.

## Privacy

* The raw synthetic member id is **never a cache key**: the key is a salted SHA-256 hex of `eligibility:member:<id>`
  (`MemberKeyHasher`), so the id is not even stored in the cache map. The SDK then fingerprints that key again
  (`sha256:<16 hex>`) for telemetry. Raw keys and cached values never leave the process.
* Only a minimal derived result is cached, never a full member or authorization record.
* Logs contain counts only, never ids, keys, values or request bodies. Error responses never echo input.
