# CATCHY: 3-minute demo script for judges

**Pitch (15 s):** "Acentra's services hit databases and rules repositories repeatedly. CATCHY is a reusable, thread-safe Java
cache SDK plus a central observability dashboard, so engineers can see which cache is healthy, which is wasting memory, and
whether LRU or LFU would serve them better, without ever sending PHI off the service."

## Before you start (once)

```bash
scripts/start-all.sh        # Windows: scripts\start-all.bat
scripts/demo-load.sh        # optional warm-up so the dashboard is not empty
```
Open **http://localhost:5173** and choose the **ENGINEER** role (one click).

## Script

| Time | Do | Say / point at |
|---|---|---|
| 0:00 | **Overview** page | Projects/applications/regions monitored, overall hit rate, *estimated* memory, **source calls avoided**, active alerts. "Every number is safe metadata - no keys, no values." |
| 0:25 | Click the **Claims Service** card | Health badge with *exact reasons*, hit/miss donut, memory bar, region table. Sort by "Lowest hit rate" to find the worst region. |
| 0:50 | `curl -X POST localhost:8091/api/demo/claims/workload/repeated -d '{"requests":400}' -H 'Content-Type: application/json'` then watch | Hit rate and **source calls avoided** climb within ~3 s (live polling). LFU protects the hot rule sets. |
| 1:10 | `.../claims/workload/changing` | Working set shifts; evictions rise. Open `claim-rules` -> **Eviction X-ray**: "Entry limit reached; least-frequently-used entry ... Released 1.8 KB". |
| 1:35 | **Policy Arena** (or Simulations -> *policy comparison*) | "LRU wins the changing pattern, LFU wins the stable-popularity pattern." Show shadow LRU vs LFU bars, confidence, reason. **Request switch** as ENGINEER. |
| 2:00 | Switch role to **ADMIN** (top right), open the request, **Approve** | "Automation never changes production policy - a person approves, and it's in the **audit log**." Within seconds the region's active policy changes (SDK polled the approved decision). Open **Audit log**. |
| 2:25 | `curl -X POST localhost:8091/api/demo/claims/workload/expire ...` | TTL expiry: MISS + EXPIRED events, expirations chart rises. "TTL is checked before LRU/LFU: an expired entry is never a hit." |
| 2:40 | `curl -X POST localhost:8092/api/demo/eligibility/workload/high-churn ...` | Memory-limit evictions on `eligibility-summary` (HIGH risk). Open its page: health goes WARNING with reasons. |
| 2:50 | Open `authorization-decision` region | "CRITICAL region: the cache supports preliminary display only; final decisions *require* source validation. A cache must never make an unsafe healthcare decision from stale data." |
| 3:00 | Close | "SDK + telemetry + dashboard, thread-safe, tested, privacy-first, and explicit about its limitations." |

## Optional extras (if asked)

* **Stampede shield**: `curl -X POST localhost:8091/api/demo/claims/workload/stampede` (concurrent callers, one uncached key),
  then read `concurrentRequestsCoalesced` in `GET localhost:8091/api/demo/claims/cache`: many requests, one source call.
* **Privacy proof**: `curl -X POST localhost:8090/api/v1/telemetry/events/batch -H "X-AcentraCache-Key: $KEY" -d '{"events":[{"rawKey":"member-1"}]}'`
  returns 400 naming the field, never storing it; Audit log shows `TELEMETRY_REJECTED`.
* **Telemetry outage**: stop the telemetry service; the demo services keep serving (failures are counted, batches retried with
  bounded backoff); restart it and the dashboard recovers.
* **SDK tests**: `./mvnw -pl acentra-cache-sdk test` (75 tests: LRU/LFU/TTL/memory, concurrency, stampede, telemetry client).
* **Dev without a backend**: `cd dashboard && npm run dev:mock`.

## If something looks empty

The dashboard shows data only after the demo services send telemetry (every ~2 s). Run `scripts/demo-load.sh`, or use
**Simulations** (ENGINEER/ADMIN), which generates events inside the telemetry service itself.
