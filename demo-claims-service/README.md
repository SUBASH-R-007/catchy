# demo-claims-service

Synthetic **claims-processing** application for CATCHY (AcentraCache Insight). It uses the AcentraCache SDK exactly like a
real Acentra application would and sends **safe telemetry only** (key fingerprints, counters, events) to the telemetry
service. **All data is synthetic. There is no patient data anywhere.**

* Application name in the dashboard: `claims-service` - port **8091**
* Java 21, Spring Boot 3.5.16, depends on `com.acentra.cache:acentra-cache-sdk:1.0.0-SNAPSHOT` (install it first:
  `mvn -f acentra-cache-sdk/pom.xml install`)

## Run

```bash
mvn -f demo-claims-service/pom.xml test                # tests (no network; telemetry is captured in-process)
mvn -f demo-claims-service/pom.xml package -DskipTests
java -jar demo-claims-service/target/demo-claims-service-1.0.0-SNAPSHOT.jar
```

With no API key the service still works fully; it just does not send telemetry (one INFO line says so).
To connect it to the telemetry service and the dashboard:

```bash
export CATCHY_TELEMETRY_URL=http://localhost:8090
export CATCHY_CLAIMS_API_KEY=<API key created for claims-service in the dashboard / seeded dev key>
java -jar demo-claims-service/target/demo-claims-service-1.0.0-SNAPSHOT.jar
```

PowerShell: `$env:CATCHY_CLAIMS_API_KEY = "..."; java -jar ...`

## Environment variables (no secrets in code or in `application.yml`)

| Variable | Default | Meaning |
|---|---|---|
| `CATCHY_TELEMETRY_URL` | `http://localhost:8090` | Telemetry service base URL |
| `CATCHY_CLAIMS_API_KEY` | *(empty)* | Application API key. Blank or missing means telemetry is **disabled** |
| `CATCHY_KEY_SALT` | *(empty)* | Salt for key fingerprints. Blank means the SDK uses a random per-process salt |
| `CATCHY_ENVIRONMENT` | `staging` | Environment label sent with telemetry |
| `SERVER_PORT` | `8091` | HTTP port (instance id is `claims-service-<port>`) |
| `CATCHY_DASHBOARD_URL` | `http://localhost:5173` | Only used for the hint text in workload summaries |

The SDK snapshots and flushes every 2 s, so the dashboard updates almost live.

## Cache regions

| Region | Policy | Risk | TTL | Capacity | Notes |
|---|---|---|---|---|---|
| `claim-rules` | LFU | MEDIUM | 15 min | 40 entries, 1 MB | synthetic rule bundles (~1.5 KB each), victim cache on, routine event sampling 1/10. Small on purpose so the workloads visibly evict |
| `provider-directory` | LRU | LOW | 1 h | 200 entries, 4 MB | synthetic provider-group summaries, `staleWhileRevalidate` with a 30 s grace (allowed for LOW risk only) |

`SimulatedSource` fakes the database/API: every call sleeps 10-30 ms, is counted, and returns deterministic data derived
from the id. Reads use `cache.getOrLoad(...)`, so the **stampede shield** applies (concurrent misses share one source call).

## Endpoints

```bash
curl localhost:8091/api/health
curl localhost:8091/api/claims/rules/RS-2026-A          # servedFrom: SOURCE, then CACHE on repeat
curl localhost:8091/api/providers/PG-042                # servedFrom: SOURCE, then CACHE
curl localhost:8091/api/demo/claims/cache               # local view: per-region metrics + health + recommendation
curl -X POST localhost:8091/api/demo/claims/cache/clear # empties the regions (counters are kept)
```

Ids must match `^[A-Za-z0-9-]{1,40}$`, otherwise `400` (the rejected value is never echoed or logged).

### Synthetic workloads (synchronous, a few seconds at most)

All accept an optional body `{ "requests": 300 }` (10-5000; `expire` defaults to 60 and `stampede` to 24 concurrent callers).

```bash
curl -X POST localhost:8091/api/demo/claims/workload/repeated
curl -X POST localhost:8091/api/demo/claims/workload/changing -H 'Content-Type: application/json' -d '{"requests": 600}'
curl -X POST localhost:8091/api/demo/claims/workload/expire
curl -X POST localhost:8091/api/demo/claims/workload/stampede
```

| Workload | Pattern | What you see |
|---|---|---|
| `repeated` | a few hot rule sets dominate, with periodic cold scans larger than the cache | good for **LFU**; under LRU every scan flushes the hot sets |
| `changing` | the popular working set slides over time | good for **LRU**; LFU keeps stale favourites. After a couple of runs the Policy Arena recommends *Switch to LRU* (approval still required) |
| `expire` | 2 s TTL entries are loaded, read while fresh (HIT), then read after expiry (MISS + EXPIRED) | expirations. With a full LFU region, brand-new (frequency 0) entries are the first eviction victims, so most expirations come from `provider-directory` |
| `stampede` | 24 concurrent requests for one uncached rule set | 1 source call, 23 coalesced |

Each returns a summary: `requests`, `cacheHits`/`cacheMisses`, `sourceCallsMade`, `sourceCallsAvoided`, `evictions`,
`expirations`, a per-region breakdown (active policy, entry/memory-limit evictions, size/capacity) and a hint. Numbers are
deltas of the SDK metrics taken around the run. Runs stop after 10 s and report `truncated: true` if they could not finish.

## Connecting to the dashboard

Start the telemetry service (port 8090) and the dashboard (`http://localhost:5173`), set `CATCHY_CLAIMS_API_KEY`, start this
service, then run the workloads above. `claims-service` appears with its two regions; the Eviction X-ray shows each decision
(`HIT`, `MISS`, `ENTRY_LIMIT_EVICTED`, `EXPIRED`, `REFRESH_COALESCED`, ...) with key **fingerprints** (`sha256:...`) only.
Policy changes are never applied automatically: an engineer approves them in the dashboard and the SDK then applies them.

## Privacy

* Only rule-set and provider-group ids (non-patient-specific) are used as keys, and the SDK sends only salted SHA-256
  fingerprints of them. Raw keys and cached values never leave the process.
* Logs contain counts only, never ids, keys, values or request bodies. Error responses never echo input.
