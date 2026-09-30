# CATCHY — Telemetry Service API

> **This document is the contract.** The telemetry service, the React dashboard, the SDK telemetry
> client, the demo services and the Postman collection are all built against the shapes below.
> If you change a shape, change it here first.

Base URL (local): `http://localhost:8090`

All bodies are JSON (`Content-Type: application/json`). JSON property names are `camelCase`.

| Convention | Rule |
|---|---|
| Timestamps | ISO-8601 UTC strings, e.g. `"2026-09-30T09:15:30.123Z"` |
| Percentages | Numbers in `0..100`, rounded to 2 decimals (`hitRate`, `missRate`, `memoryUtilizationPercent` …) |
| Byte counts | Integers. Always **estimated** cache memory — never exact JVM heap |
| IDs | Numeric (`Long`) for projects / applications / api-keys / requests / events / audit entries |
| Nulls | Optional fields are `null` (or absent); clients must treat both the same |
| Unknown request fields | **Rejected with 400** on telemetry endpoints (privacy guard); ignored elsewhere |

## Authentication

Two separate mechanisms:

| Audience | Mechanism | Used for |
|---|---|---|
| Acentra applications (SDK) | `X-AcentraCache-Key: <application-api-key>` | `/api/v1/telemetry/**` only |
| Dashboard users | `Authorization: Bearer <token>` | everything else under `/api/v1/**` |

### Roles

| Role | Can do |
|---|---|
| `VIEWER` | read dashboards, metrics, events, recommendations |
| `ENGINEER` | everything `VIEWER` can + run simulations, evaluate recommendations, **request** and **approve/reject** policy changes (cannot approve own request) |
| `ADMIN` | everything `ENGINEER` can + create projects/applications, create/revoke API keys, change cache configuration, approve own requests, read audit logs |

Endpoint tables below show the **minimum role**. `403` is returned for insufficient role, `401` for
missing/invalid/expired credentials. Every denial of a sensitive action is written to the audit log.

### `POST /api/v1/auth/login` (public)
```json
// request
{ "username": "engineer", "password": "…" }
// response 200
{ "token": "<opaque-signed-token>", "username": "engineer", "role": "ENGINEER", "expiresAt": "2026-09-30T17:15:30Z" }
```
Users: `admin` / `engineer` / `viewer`. Passwords come from env (`CATCHY_ADMIN_PASSWORD`,
`CATCHY_ENGINEER_PASSWORD`, `CATCHY_VIEWER_PASSWORD`), stored BCrypt-hashed.

### `POST /api/v1/auth/demo-login` (public, **only when `CATCHY_DEMO_MODE=true`**)
```json
{ "role": "ENGINEER" }   // → same response as /login; 404 when demo mode is off
```
One-click role selector for the demo. Audited like a normal login.

### `GET /api/v1/auth/me` (any role)
```json
{ "username": "engineer", "role": "ENGINEER" }
```

### `GET /api/v1/health` (public)
```json
{ "status": "UP", "service": "telemetry-service", "version": "1.0.0", "demoMode": true }
```

## Errors

Every error (including validation, auth, 404, 5xx) has this shape. **No stack traces, ever.**
```json
{
  "timestamp": "2026-09-30T09:15:30.123Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "path": "/api/v1/projects",
  "details": ["name: must not be blank"]
}
```

## Shared types

### Enums
```
EvictionPolicy   : LRU | LFU
CacheRiskLevel   : LOW | MEDIUM | HIGH | CRITICAL
CacheHealthStatus: EXCELLENT | GOOD | WARNING | CRITICAL | UNKNOWN
EventSeverity    : INFO | WARN | CRITICAL
CacheAction      : HIT | MISS | PUT | REMOVE | CLEAR | EXPIRED | EVICTED | MEMORY_EVICTED |
                   ENTRY_LIMIT_EVICTED | POLICY_CHANGED | CLEANUP | POLICY_RECOMMENDATION |
                   VICTIM_HIT | STALE_SERVED | REFRESH_STARTED | REFRESH_COALESCED |
                   REFRESH_FAILED | SOURCE_VALIDATED | CONFIG_CHANGED
```
*Eviction family* = `EVICTED`, `MEMORY_EVICTED`, `ENTRY_LIMIT_EVICTED`. *Hit family* = `HIT`, `VICTIM_HIT`,
`STALE_SERVED`. `MISS` is a miss. Exactly one event is emitted per eviction: `ENTRY_LIMIT_EVICTED` when
the entry limit forced it, `MEMORY_EVICTED` when the memory limit forced it, plain `EVICTED` for other
removals (victim-layer overflow, capacity reduced by configuration).

### `Health`
```json
{
  "status": "WARNING",
  "score": 52,
  "reasons": [
    "Hit rate is 48.2%, below 65% target.",
    "Memory utilization is 93%.",
    "436 evictions occurred in the last 10 minutes."
  ]
}
```
`score` is `0..100`. `reasons` is never empty for `WARNING`/`CRITICAL`/`UNKNOWN`; for `GOOD`/`EXCELLENT` it
lists the passing conditions (e.g. `"Hit rate is 91.4%, above 85% target."`).

### `MetricTotals`  (flat counters — same names everywhere)
```json
{
  "hits": 9140, "misses": 860, "hitRate": 91.4, "missRate": 8.6,
  "puts": 900, "removes": 3, "clears": 0, "evictions": 120, "expirations": 40,
  "size": 410, "capacity": 500,
  "estimatedMemoryUsageBytes": 138412032, "maximumMemoryBytes": 268435456, "memoryUtilizationPercent": 51.56,
  "lruEvictions": 0, "lfuEvictions": 120,
  "evictionsDueToEntryLimit": 100, "evictionsDueToMemoryLimit": 20,
  "averageGetLatencyMs": 0.012, "averagePutLatencyMs": 0.031,
  "sourceCallsAvoided": 9140,
  "telemetryEventsSent": 512, "telemetryEventsFailed": 0,
  "l1Hits": 9100, "victimHits": 40, "sourceMisses": 860, "victimEvictions": 5, "overallHitRate": 91.4,
  "refreshesStarted": 12, "concurrentRequestsCoalesced": 180,
  "sourceCallsAvoidedByStampedeShield": 180, "refreshFailures": 0,
  "staleServed": 0, "staleCorrections": 0
}
```
`hitRate = hits / (hits + misses) × 100`, `missRate = misses / (hits + misses) × 100`; both `0` when there
are no requests; otherwise `hitRate + missRate = 100`. `overallHitRate = (l1Hits + victimHits) / requests × 100`.
`size`/`capacity`/`estimatedMemoryUsageBytes`/`maximumMemoryBytes` describe the primary (L1) layer.
Aggregated latencies are weighted by operation counts.

### `RegionMetrics`  = `MetricTotals` + identity + region state
```json
{
  "applicationId": 1, "applicationName": "claims-service", "environment": "staging",
  "cacheRegion": "claim-rules", "riskLevel": "MEDIUM", "activePolicy": "LFU",
  "defaultTtlMs": 900000,
  "victimEnabled": true, "victimSize": 12, "victimCapacity": 75,
  "recentWindow": { "minutes": 10, "hits": 820, "misses": 40, "puts": 45, "evictions": 12, "expirations": 3 },
  "shadow": {
    "requests": 10000, "windowRequests": 1000,
    "lruHitRate": 69.4, "lfuHitRate": 80.9, "topKeyConcentrationPercent": 78.5
  },
  "health": { "status": "GOOD", "score": 78, "reasons": ["…"] },
  "instanceCount": 1,
  "lastUpdated": "2026-09-30T09:15:30Z",
  "…all MetricTotals fields…": 0
}
```
`shadow` is `null` until the SDK has reported shadow data. `lruHitRate`/`lfuHitRate` are the Policy Arena's
**simulated** hit rates over the most recent `windowRequests` reads — not live measurements.

### `ApplicationSummary`
```json
{
  "applicationId": 1, "projectId": 1, "projectName": "Claims Platform",
  "name": "claims-service", "displayName": "Claims Service", "environment": "staging",
  "regionCount": 2,
  "totals": { "…MetricTotals…": 0 },
  "health": { "status": "GOOD", "score": 80, "reasons": ["…"] },
  "activePolicies": ["LFU", "LRU"], "policyLabel": "MIXED",
  "recommendationSummary": "Keep LFU",
  "lastTelemetryAt": "2026-09-30T09:15:30Z", "secondsSinceLastTelemetry": 3
}
```
`health` is the **worst** region health. `policyLabel` is the policy when all regions agree, else `"MIXED"`;
`"NONE"` when no region has reported. `recommendationSummary` is `"Keep LFU"`, `"Switch claim-rules to LFU"`
or `"No data yet"`. `lastTelemetryAt` is `null` (and `secondsSinceLastTelemetry` `null`) if nothing was received.

### `Alert`
```json
{ "id": "1:claim-rules:HIT_RATE", "severity": "WARNING", "applicationId": 1, "applicationName": "claims-service",
  "cacheRegion": "claim-rules", "message": "Hit rate is 48.2%, below 65% target.", "since": "2026-09-30T09:10:00Z" }
```
`severity` ∈ `INFO | WARNING | CRITICAL`. One alert per health reason of every `WARNING`/`CRITICAL` region.

### `Event` (Eviction X-ray entry; response shape)
```json
{
  "id": 981, "timestamp": "2026-09-30T09:15:08.120Z",
  "applicationName": "claims-service", "environment": "staging", "cacheRegion": "claim-rules",
  "action": "ENTRY_LIMIT_EVICTED",
  "keyFingerprint": "sha256:4a1d9c03be7f2a61",
  "reason": "Entry limit reached (500); least-recently-used entry had not been accessed for 74 seconds. Released 1.8 KB.",
  "policy": "LRU", "frequency": 2, "lastAccessAgeMs": 74000, "remainingTtlMs": 45000,
  "estimatedEntrySizeBytes": 1800,
  "cacheSizeBefore": 500, "cacheSizeAfter": 499,
  "memoryBeforeBytes": 901000, "memoryAfterBytes": 899200,
  "valueReturned": false, "severity": "INFO", "latencyMs": 0.02
}
```
`keyFingerprint` is `null` in category-only mode. Raw keys and values never appear anywhere.

### `Recommendation`  (Policy Arena)
```json
{
  "id": 7, "applicationId": 1, "applicationName": "claims-service", "cacheRegion": "claim-rules",
  "currentPolicy": "LRU", "recommendedPolicy": "LFU", "action": "SWITCH", "summary": "Switch to LFU",
  "lruShadowHitRate": 69.4, "lfuShadowHitRate": 80.9, "improvementPercent": 11.5, "confidence": 92,
  "reason": "A small stable set of keys dominates repeated requests (top 20% of keys receive 78.5% of requests); LFU retains them.",
  "aiExplanation": null,
  "sampleSize": 1000, "minimumSampleMet": true, "cooldownActive": false, "cooldownEndsAt": null,
  "approvalRequired": true, "createdAt": "2026-09-30T09:15:30Z", "pendingRequestId": null
}
```
`action` is `SWITCH` or `KEEP`. `improvementPercent = shadowRate(alternative) − shadowRate(current)` and may be
negative. `SWITCH` requires: sample ≥ minimum (100), improvement ≥ threshold (5 points), and no active cooldown.
`approvalRequired` is always `true`. `aiExplanation` is only non-null when the optional Ollama advisor is enabled
and reachable; it never influences `action`. `pendingRequestId` is set when a policy-change request for that
region is already `PENDING`.

### `PolicyChangeRequest`
```json
{
  "id": 3, "applicationId": 1, "applicationName": "claims-service", "cacheRegion": "claim-rules",
  "currentPolicy": "LRU", "requestedPolicy": "LFU", "reason": "Policy Arena recommends LFU (+11.5 pts).",
  "status": "PENDING",
  "requestedBy": "engineer", "decidedBy": null, "decisionNote": null,
  "createdAt": "2026-09-30T09:16:00Z", "decidedAt": null, "appliedAt": null, "recommendationId": 7
}
```
`status`: `PENDING → APPROVED → APPLIED` or `PENDING → REJECTED`. `APPLIED` is set when a later snapshot from
the SDK reports the region running the requested policy.

### Timeline
```json
{
  "cacheRegion": "claim-rules", "bucketSeconds": 10,
  "points": [
    { "bucketStart": "2026-09-30T09:15:00Z", "hits": 120, "misses": 14, "puts": 14, "evictions": 2, "expirations": 0, "hitRate": 89.55 }
  ]
}
```
Points are **deltas** between consecutive cumulative snapshots, bucketed, oldest first, empty buckets included
(zeros; `hitRate` 0) so charts have a continuous x-axis. `cacheRegion` is `null` for app-level / global timelines.

---

## Projects

| Method & path | Min role | Notes |
|---|---|---|
| `POST /api/v1/projects` | ADMIN | → `201` `Project` |
| `GET /api/v1/projects` | VIEWER | → `Project[]` |
| `GET /api/v1/projects/{projectId}` | VIEWER | → `Project` |

```json
// POST request
{ "name": "Claims Platform", "description": "Claims adjudication and processing services" }
// Project
{ "id": 1, "name": "Claims Platform", "description": "…", "createdAt": "…", "applicationCount": 1 }
```
`name`: 2–80 chars, unique (case-insensitive) → `409` on duplicate.

## Applications

| Method & path | Min role | Notes |
|---|---|---|
| `POST /api/v1/projects/{projectId}/applications` | ADMIN | → `201` `Application` |
| `GET /api/v1/projects/{projectId}/applications` | VIEWER | → `Application[]` |
| `GET /api/v1/applications` | VIEWER | all applications (convenience) |
| `GET /api/v1/applications/{applicationId}` | VIEWER | → `Application` |

```json
// POST request
{ "name": "claims-service", "displayName": "Claims Service", "environment": "staging", "description": "…" }
// Application
{ "id": 1, "projectId": 1, "projectName": "Claims Platform", "name": "claims-service",
  "displayName": "Claims Service", "environment": "staging", "description": "…",
  "createdAt": "…", "lastTelemetryAt": null, "regionCount": 0 }
```
`name`: regex `^[a-z0-9][a-z0-9-]{1,62}$`, unique per `(name, environment)`. `environment`: `^[a-z][a-z0-9-]{1,30}$`.

## API keys

| Method & path | Min role | Notes |
|---|---|---|
| `POST /api/v1/applications/{applicationId}/api-keys` | ADMIN | → `201` `ApiKeyCreated` (plaintext key shown **once**) |
| `GET /api/v1/applications/{applicationId}/api-keys` | ADMIN | → `ApiKey[]` (masked) |
| `DELETE /api/v1/api-keys/{apiKeyId}` | ADMIN | revoke → `204`; later use → `401` |

```json
// POST request
{ "label": "staging pod 1" }
// ApiKeyCreated
{ "id": 4, "applicationId": 1, "label": "staging pod 1", "keyPrefix": "acc_ab12cd34",
  "maskedKey": "acc_ab12cd34_••••••••••••••••", "apiKey": "acc_ab12cd34_<32 hex chars>", "createdAt": "…" }
// ApiKey (list)
{ "id": 4, "applicationId": 1, "label": "staging pod 1", "keyPrefix": "acc_ab12cd34",
  "maskedKey": "acc_ab12cd34_••••••••••••••••", "createdAt": "…", "lastUsedAt": null, "revokedAt": null, "active": true }
```
Key format: `acc_<8 hex>_<32 hex>`. Only a SHA-256 hash of the full key and the prefix are stored. The plaintext is
never logged and never returned again.

---

## Telemetry ingestion (API-key authenticated)

Header on every call: `X-AcentraCache-Key: <application-api-key>`. The key is authoritative for *which
application* the data belongs to. If `applicationName`/`environment` are present and differ from the key's
application → `403`. Revoked/unknown key → `401`.

**Privacy guard:** these endpoints bind with *fail-on-unknown-properties*. Any extra field (e.g. `rawKey`,
`value`, `memberId`, `patientName`, `responseBody`) → `400` with the **field names** (never the values) in
`details`, and an audit entry `TELEMETRY_REJECTED`. `keyFingerprint` must match `^sha256:[0-9a-f]{8,64}$` or be
`null`. `reason` ≤ 400 chars. Request bodies are size-limited (batch ≤ 500 events, ≤ 1 MB).

### `TelemetryEvent` (request shape)
```json
{
  "timestamp": "2026-09-30T09:15:08.120Z",
  "applicationName": "claims-service", "environment": "staging",
  "cacheRegion": "claim-rules",
  "action": "HIT",
  "keyFingerprint": "sha256:4a1d9c03be7f2a61",
  "reason": "Valid entry returned",
  "policy": "LFU", "frequency": 3, "lastAccessAgeMs": 1200, "remainingTtlMs": 45000,
  "estimatedEntrySizeBytes": 1800,
  "cacheSizeBefore": 410, "cacheSizeAfter": 410,
  "memoryBeforeBytes": 738000, "memoryAfterBytes": 738000,
  "valueReturned": true, "severity": "INFO", "latencyMs": 0.012
}
```
Required: `timestamp`, `cacheRegion` (`^[a-z0-9][a-z0-9-]{0,62}$`), `action`, `policy`. All numeric fields ≥ 0.
`applicationName`/`environment` are optional inside a batch.

### `POST /api/v1/telemetry/events` → `202`
Body: one `TelemetryEvent` (here `applicationName` + `environment` are required). Response: `{ "acceptedEvents": 1, "acceptedSnapshots": 0 }`.

### `POST /api/v1/telemetry/events/batch` → `202`
```json
{
  "applicationName": "claims-service", "environment": "staging",
  "instanceId": "claims-service-8091", "sdkVersion": "1.0.0", "sentAt": "2026-09-30T09:15:10Z",
  "events": [ { "…TelemetryEvent…": 0 } ],
  "snapshots": [ { "…RegionSnapshot…": 0 } ]
}
// response
{ "acceptedEvents": 50, "acceptedSnapshots": 2 }
```
`events` and `snapshots` may each be empty. `instanceId`: `^[A-Za-z0-9._-]{1,80}$`.

### `RegionSnapshot` (cumulative counters **since the SDK instance started**)
Sent by the SDK every few seconds per region. This is the source of truth for aggregated metrics (events
are sampled for routine HIT/PUT, snapshots are exact).
```json
{
  "capturedAt": "2026-09-30T09:15:10Z",
  "cacheRegion": "claim-rules", "riskLevel": "MEDIUM", "activePolicy": "LFU",
  "hits": 9140, "misses": 860, "puts": 900, "removes": 3, "clears": 0,
  "evictions": 120, "expirations": 40,
  "size": 410, "capacity": 500,
  "estimatedMemoryUsageBytes": 738000, "maximumMemoryBytes": 268435456,
  "lruEvictions": 0, "lfuEvictions": 120,
  "evictionsDueToEntryLimit": 100, "evictionsDueToMemoryLimit": 20,
  "averageGetLatencyMs": 0.012, "averagePutLatencyMs": 0.031,
  "sourceCallsAvoided": 9140,
  "telemetryEventsSent": 512, "telemetryEventsFailed": 0, "telemetryFailureStreak": 0,
  "l1Hits": 9100, "victimHits": 40, "sourceMisses": 860, "victimEvictions": 5,
  "victimEnabled": true, "victimSize": 12, "victimCapacity": 75,
  "refreshesStarted": 12, "concurrentRequestsCoalesced": 180,
  "sourceCallsAvoidedByStampedeShield": 180, "refreshFailures": 0,
  "sourceCalls": 872, "sourceErrors": 0,
  "staleServed": 0, "staleCorrections": 0, "staleDataViolations": 0,
  "defaultTtlMs": 900000,
  "lastPolicyChangeAt": null,
  "recentWindow": { "minutes": 10, "hits": 820, "misses": 40, "puts": 45, "evictions": 12, "expirations": 3 },
  "shadow": { "requests": 10000, "windowRequests": 1000, "lruHitRate": 69.4, "lfuHitRate": 80.9, "topKeyConcentrationPercent": 78.5 }
}
```
`shadow` may be `null`. Counters are monotonic per instance; the service derives deltas (and must treat a
decrease as an instance restart, i.e. a new baseline — never produce negative deltas). The service keeps the
latest snapshot per `(application, region, instanceId)` and **sums** across instances for displayed totals.

### `GET /api/v1/telemetry/control`  (API-key authenticated, polled by the SDK)
Approved engineer decisions the SDK should apply. **Automation never changes a policy by itself.**
```json
{
  "regions": [
    {
      "cacheRegion": "claim-rules",
      "desiredPolicy": "LFU", "policyRequestId": 3,
      "tuning": { "maximumEntries": 800, "maximumMemoryBytes": 536870912, "defaultTtlMs": 600000 },
      "tuningVersion": 2
    }
  ]
}
```
`desiredPolicy` is `null` unless a policy-change request is `APPROVED` or `APPLIED`; `tuning` is `null` unless an
admin set an override (each tuning field nullable). The SDK applies a change only if it differs from its current
value and then emits `POLICY_CHANGED` / `CONFIG_CHANGED`. Response is always `200` with `{ "regions": [] }` when
nothing is pending.

---

## Metrics (VIEWER+)

| Method & path | Response |
|---|---|
| `GET /api/v1/overview` | `Overview` (dashboard home) |
| `GET /api/v1/projects/{projectId}/metrics` | `ProjectMetrics` |
| `GET /api/v1/applications/{applicationId}/metrics` | `ApplicationMetrics` |
| `GET /api/v1/applications/{applicationId}/regions` | `RegionMetrics[]` |
| `GET /api/v1/applications/{applicationId}/regions/{regionName}/metrics` | `RegionMetrics` (`404` if unknown region) |
| `GET /api/v1/applications/{applicationId}/regions/{regionName}/health` | `Health` |
| `GET /api/v1/applications/{applicationId}/regions/{regionName}/events?limit=100&actions=MISS,EVICTED&since=<instant>` | `Event[]`, newest first (`limit` ≤ 500; `actions` optional comma list) |
| `GET /api/v1/applications/{applicationId}/events?limit=100&actions=…&since=…` | `Event[]` across all regions of the app |
| `GET /api/v1/applications/{applicationId}/regions/{regionName}/timeline?minutes=15&bucketSeconds=10` | `Timeline` |
| `GET /api/v1/applications/{applicationId}/timeline?minutes=15&bucketSeconds=10` | `Timeline` (all regions of app summed) |
| `GET /api/v1/timeline?minutes=15&bucketSeconds=10` | `Timeline` (everything summed) |

`minutes` 1–120 (default 15), `bucketSeconds` 5–300 (default 10).

```json
// Overview
{
  "generatedAt": "…",
  "projectsMonitored": 3, "applicationsMonitored": 2, "cacheRegionsMonitored": 5,
  "totals": { "…MetricTotals…": 0 },
  "activeAlerts": [ { "…Alert…": 0 } ],
  "latestRecommendations": [ { "…Recommendation…": 0 } ],
  "applications": [ { "…ApplicationSummary…": 0 } ],
  "regions": [ { "…RegionMetrics…": 0 } ]
}
// ProjectMetrics
{ "projectId": 1, "projectName": "Claims Platform", "applicationCount": 1, "regionCount": 2,
  "totals": { "…MetricTotals…": 0 }, "health": { "…Health…": 0 },
  "applications": [ { "…ApplicationSummary…": 0 } ] }
// ApplicationMetrics  = ApplicationSummary + regions
{ "…ApplicationSummary fields…": 0, "regions": [ { "…RegionMetrics…": 0 } ] }
```
`cacheRegionsMonitored` counts distinct `(application, region)` pairs that have reported. With no telemetry
yet, all lists are empty and all totals are zero (never `null`).

## Region configuration

| Method & path | Min role |
|---|---|
| `GET /api/v1/applications/{applicationId}/regions/{regionName}/config` | VIEWER |
| `PUT /api/v1/applications/{applicationId}/regions/{regionName}/config` | ADMIN |

```json
// PUT request (all fields optional; at least one required)
{ "maximumEntries": 800, "maximumMemoryBytes": 536870912, "defaultTtlMs": 600000, "reason": "Raise capacity for month-end load" }
// RegionConfig response (both GET and PUT)
{
  "cacheRegion": "claim-rules", "riskLevel": "MEDIUM",
  "reported": { "maximumEntries": 500, "maximumMemoryBytes": 268435456, "defaultTtlMs": 900000, "activePolicy": "LFU" },
  "desired":  { "maximumEntries": 800, "maximumMemoryBytes": null, "defaultTtlMs": null },
  "pending": true, "tuningVersion": 2, "updatedBy": "admin", "updatedAt": "…"
}
```
Bounds: `maximumEntries` 1–10,000,000; `maximumMemoryBytes` ≥ 1024; `defaultTtlMs` ≥ 1000. `desired` is `null` if never
overridden. `pending` is `true` while `desired` differs from `reported`. Delivered to the SDK via
`/telemetry/control`. Audited (`CONFIG_CHANGE_REQUESTED`, `CONFIG_CHANGE_APPLIED`).

## Recommendations and policy changes

| Method & path | Min role | Response |
|---|---|---|
| `GET /api/v1/applications/{applicationId}/recommendations` | VIEWER | `Recommendation[]` — latest per region |
| `POST /api/v1/applications/{applicationId}/recommendations/evaluate` | ENGINEER | re-evaluates now → `Recommendation[]` (audited) |
| `POST /api/v1/applications/{applicationId}/policy-change-requests` | ENGINEER | `201` `PolicyChangeRequest` |
| `GET /api/v1/applications/{applicationId}/policy-change-requests` | VIEWER | `PolicyChangeRequest[]`, newest first |
| `POST /api/v1/policy-change-requests/{requestId}/approve` | ENGINEER | `PolicyChangeRequest` (`409` if not `PENDING`) |
| `POST /api/v1/policy-change-requests/{requestId}/reject` | ENGINEER | `PolicyChangeRequest` |

```json
// create request body
{ "cacheRegion": "claim-rules", "requestedPolicy": "LFU", "reason": "Policy Arena recommends LFU", "recommendationId": 7 }
// approve / reject body (optional)
{ "note": "Looks right; shadow gain is stable." }
```
Rules: an engineer cannot approve/reject their **own** request (`403`, audited) — `ADMIN` can. Only one `PENDING`
request per region (`409`). `requestedPolicy` must differ from the current policy (`400`). The recommendation
engine is **deterministic**; the optional Ollama text only fills `aiExplanation`. Recommendations are also
refreshed by a background job every 15 s. Request / approval / rejection / application are all audited.

## Audit log

| Method & path | Min role |
|---|---|
| `GET /api/v1/audit-logs?limit=100&action=POLICY_CHANGE_APPROVED&applicationId=1` | ADMIN |

```json
{ "id": 55, "timestamp": "…", "actor": "admin", "actorRole": "ADMIN", "action": "API_KEY_CREATED",
  "targetType": "API_KEY", "targetId": "4", "applicationId": 1,
  "details": "Created key 'staging pod 1' (acc_ab12cd34)", "outcome": "SUCCESS" }
```
`limit` ≤ 500, newest first. `outcome` ∈ `SUCCESS | DENIED | FAILURE`. `actorRole` ∈ `VIEWER | ENGINEER | ADMIN | SYSTEM`
(`SYSTEM` = an action the service performs itself, e.g. `POLICY_CHANGE_APPLIED` when an SDK snapshot confirms the new policy). Audited actions:
`LOGIN_SUCCESS`, `LOGIN_FAILURE`, `PROJECT_CREATED`, `APPLICATION_CREATED`, `API_KEY_CREATED`, `API_KEY_REVOKED`,
`POLICY_CHANGE_REQUESTED`, `POLICY_CHANGE_APPROVED`, `POLICY_CHANGE_REJECTED`, `POLICY_CHANGE_APPLIED`,
`CONFIG_CHANGE_REQUESTED`, `CONFIG_CHANGE_APPLIED`, `RECOMMENDATION_EVALUATED`, `SIMULATION_RUN`,
`TELEMETRY_REJECTED`, `ACCESS_DENIED`. Details never contain keys, values or secrets.

## Simulation (ENGINEER+)

| Method & path |
|---|
| `POST /api/v1/applications/{applicationId}/simulate/sample-workload` |
| `POST /api/v1/applications/{applicationId}/simulate/high-load` |
| `POST /api/v1/applications/{applicationId}/simulate/ttl-expiration` |
| `POST /api/v1/applications/{applicationId}/simulate/policy-comparison` |

Request body is optional: `{ "requests": 2000 }` (bounds 50–20,000, default per kind). Simulations run **inside the
telemetry service** with synthetic keys only, using real SDK caches whose telemetry is ingested directly (no
HTTP, no API key). They appear in the dashboard as regions of the chosen application: `sim-sample-workload`,
`sim-high-load`, `sim-ttl-expiration`, `sim-policy-lru`, `sim-policy-lfu`. All are audited (`SIMULATION_RUN`).

```json
{
  "simulationId": "a1b2c3", "kind": "policy-comparison", "applicationId": 1,
  "cacheRegions": ["sim-policy-lru", "sim-policy-lfu"],
  "startedAt": "…", "durationMs": 184,
  "summary": "LFU wins the stable-popularity pattern by 14.2 pts; LRU wins the changing pattern by 6.8 pts.",
  "steps": [ { "name": "Pattern A", "description": "LRU-friendly changing access", "detail": "A,B,C,A,D,E,D,E,F,G → LRU 40.0% / LFU 30.0%" } ],
  "hits": 1234, "misses": 766, "hitRate": 61.7, "evictions": 410, "expirations": 0,
  "comparison": [
    { "pattern": "A — LRU-friendly changing access", "lruHitRate": 62.5, "lfuHitRate": 55.7, "winner": "LRU",
      "interpretation": "Recently accessed keys matter most; LRU keeps the moving working set." },
    { "pattern": "B — LFU-friendly stable popularity", "lruHitRate": 61.0, "lfuHitRate": 75.2, "winner": "LFU",
      "interpretation": "A and B are consistently popular; LFU protects them from one-off scans." }
  ]
}
```
`comparison` is `null` except for `policy-comparison`. `ttl-expiration` must visibly produce `MISS` + `EXPIRED`
events. `high-load` must trigger entry-limit **and** memory-limit evictions.

---

## HTTP status summary

`200` OK · `201` created · `202` telemetry accepted · `204` no content · `400` validation / privacy-guard ·
`401` missing/invalid credentials · `403` insufficient role / application mismatch · `404` not found ·
`409` conflict · `413` payload too large · `429` rate limited (simple per-key limiter on ingestion) · `500` generic
(`"message": "Unexpected error"`, nothing else).
