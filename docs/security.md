# CATCHY security and privacy model

> **This is a healthcare-aware engineering demonstration. It demonstrates privacy-conscious design but does not by itself
> establish legal or regulatory compliance certification (HIPAA or otherwise).**

## Who uses it

Acentra backend engineers, platform engineers, application support engineers and authorized administrators. End users of
Acentra products (members, providers, patients) never see cache metrics; they only benefit from faster, more stable services.

## What is (and is not) collected

The telemetry service only ever receives **safe metadata**:

| Sent | Never sent, stored, logged or displayed |
|---|---|
| application name, environment, cache region | patient names, member IDs, medical record numbers |
| operation/action, result (HIT/MISS/...), latency | claim identifiers, diagnosis or treatment information |
| policy, frequency, remaining TTL, estimated entry size | raw request or response bodies |
| **key fingerprint** (`sha256:` + 16 hex of a salted hash) or nothing | raw cache **values**, raw cache **keys** |
| aggregate counters, estimated memory, health inputs | authentication tokens, cookies, passwords, database credentials |

### Enforced in three layers

1. **SDK**: `CacheDecisionEvent` / `TelemetryEvent` / `RegionSnapshot` have no field that could carry a key or value. Loader
   failure events record only the exception *class*, never its message (source messages can contain identifiers).
2. **Wire**: the ingestion DTOs bind with *fail-on-unknown-properties*. A payload with `rawKey`, `value`, `memberId`, ... is
   rejected with 400 and the **field names only** in the response and audit record, never the values. `keyFingerprint` must match
   `^sha256:[0-9a-f]{8,64}$`; free text is length-limited.
3. **Storage/API**: no column can hold a key or value; responses are DTOs, never entities.

### Key fingerprints

`CacheKeySanitizer` hashes `salt | region | key` with SHA-256 and reports the first 16 hex chars. The salt is an application
secret (`CATCHY_KEY_SALT`) so the same member id cannot be linked across applications or brute-forced from telemetry without
the salt. **Category-only mode** omits even the fingerprint. The raw key never leaves the source application process. For the
eligibility demo the in-process cache key is itself a salted hash of the synthetic member id.

## Authentication and authorization

| Channel | Mechanism |
|---|---|
| SDK -> service | `X-AcentraCache-Key`. Keys (`acc_<8hex>_<32hex>`) are shown **once** at creation; only a SHA-256 hash and the prefix are stored; constant-time comparison; revocable; bound to one application (mismatch -> 403). |
| Dashboard -> service | `Authorization: Bearer <token>`: a compact HMAC-SHA256-signed token, 8 h expiry, secret from `CATCHY_TOKEN_SECRET`. |

Roles: **ADMIN** (manage projects/apps/keys, change configuration, approve own requests, read audit log), **ENGINEER** (view,
evaluate, simulate, request and approve/reject policy changes - not their own), **VIEWER** (read-only). Role checks are
enforced server-side; the UI hiding controls is a convenience only. Login passwords are BCrypt-hashed and come only from
environment variables; with no password set a role is reachable only via demo mode. **Demo mode** (`CATCHY_DEMO_MODE=true`) gives
one-click role login and must be `false` on any shared deployment.

## Healthcare safety of caching itself

A cache can improve speed, but **a cache must not make unsafe healthcare decisions based on stale data.**
* HIGH/CRITICAL regions cannot be configured for stale-while-revalidate (the builder throws).
* `requireFreshFromSource` makes the source mandatory before a final action (used by the demo `authorization-decision`
  region); the cache may support *preliminary display only*. Drift between cache and source is recorded and corrected.
* Health scoring treats a stale-data violation in a risky region as CRITICAL.
* Sample region guidance: `claim-rules` (non-patient rule bundles, 15 min), `provider-directory` (shared reference data, 1 h),
  `eligibility-summary` (member-specific, minimal derived result, 1-5 min, fingerprints only),
  `authorization-decision` (critical, source validation mandatory).

## Other controls

* **Audit log** for policy change requests/approvals/rejections/applications, configuration changes, API key creation and
  revocation, login success/failure, simulation runs, rejected telemetry, access denials. Details never contain keys, values or secrets.
* **Errors**: a global handler returns a fixed JSON shape; no stack traces or internal messages reach clients.
* **CORS** restricted to `CATCHY_DASHBOARD_ORIGIN` (default `http://localhost:5173`).
* **Input limits**: body size (1 MB), batch size (500 events), field patterns and lengths, bounded configuration values.
* **Rate limiting**: a simple in-memory per-API-key limiter on ingestion (demo mechanism; use a gateway for production).
* **Secrets**: nothing hard-coded; `.env` is git-ignored; `.env.example` holds clearly-labelled placeholders (including two
  dev-only placeholder API keys used to seed the demo).
* **Logging**: request bodies, keys, values and API keys are never logged; SDK warnings report status/exception class only.
* **LLM (optional, off by default)**: a local Ollama call is made only from the recommendation job, never during cache GET/PUT or
  ingestion, sends only aggregate numbers (no keys, fingerprints, values), and cannot change a recommendation.

## Limitations (be honest about them)

* Demo authentication is simple (shared-secret HMAC tokens, in-memory rate limiting, no refresh/revocation, no MFA/SSO).
* No TLS termination is included; put the services behind TLS in any real deployment. SDK -> service traffic is plain HTTP locally.
* Fingerprints are pseudonymous, not anonymous; treat telemetry as internal operational data with access control.
* Estimated memory is not JVM heap accounting; it can be off by a constant factor for exotic objects.
* The cache is in-process, per-instance, and not distributed.
* No claim of HIPAA, SOC 2 or any other certification is made. A real deployment needs a compliance review.

## Non-goals

Reading the memory of other applications, monitoring arbitrary installed apps, showing metrics to end users, storing or showing
PHI, sending raw values to telemetry, an LLM deciding on cache reads/writes, automatic policy changes without approval, exact JVM
heap claims, a distributed cache cluster.
