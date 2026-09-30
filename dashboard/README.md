# CATCHY dashboard — AcentraCache Insight

React + TypeScript (strict) + Vite dashboard for the CATCHY telemetry service. It is for Acentra
backend, platform and support engineers and admins: cache health across services, hit/miss rates,
memory, evictions and expirations, the **Policy Arena** (LRU vs LFU), the **Eviction X-ray** event
timeline, simulations, audit log and region configuration.

It shows **safe cache metadata only** (counters, sizes, key fingerprints such as
`sha256:4a1d9c03be7f2a61`). Raw keys, cached values and patient data never reach the UI.

> Healthcare-aware engineering demonstration — shows safe cache metadata only; not a compliance certification.
> (This note is a permanent footer on every page.)

The API contract is `../docs/api.md`; every type in `src/api/types.ts` follows it.

## Requirements

Node 20+ (developed on Node 24, npm 11). No global tools are needed.

## Install

```bash
cd dashboard
npm install
```

## Run against the real backend

```bash
# telemetry service on http://localhost:8090 (default)
npm run dev
# or point the dev server's /api proxy somewhere else
CATCHY_API_URL=http://telemetry.internal:8090 npm run dev          # bash
$env:CATCHY_API_URL = "http://telemetry.internal:8090"; npm run dev # PowerShell
```

Open <http://localhost:5173>. The client always calls relative `/api/v1/...` URLs; Vite proxies `/api`
to `CATCHY_API_URL` (`npm run preview` uses the same proxy). Sign in with a demo role (needs
`CATCHY_DEMO_MODE=true` on the service) or with `admin` / `engineer` / `viewer` and the passwords the
service was started with.

## Run in mock mode (no backend needed)

```bash
npm run dev:mock
```

`dev:mock` is `vite --mode mock`, which loads `.env.mock` (`VITE_MOCK_API=true`). An in-browser mock of
the whole contract answers every `/api/v1` call with a small artificial latency. It is deterministic
(seeded) and **evolves over time**: counters grow on every 3-second poll, there is an occasional
eviction burst, and these stories are built in:

| Story | Where |
|---|---|
| `member-eligibility` (HIGH risk) is deliberately **WARNING**: hit rate below target, memory ~92%, hundreds of evictions | Overview alerts, Eligibility Service |
| `authorization-decision` is a **CRITICAL-risk** region (healthcare safety note) | Authorization Service |
| Policy Arena recommends **Switch to LFU** for `member-eligibility`; everything else is **Keep** | Policy Arena |
| A seeded **PENDING** policy-change request made by `admin`: approve it as ENGINEER/ADMIN and ~6 s later the "SDK" applies LFU, the hit rate climbs and the recommendation flips to Keep | Policy Arena |
| An application with **no telemetry yet** (`provider-portal-gateway`) | Applications, Overview empty states |
| All four simulations (real LRU/LFU caches over synthetic keys) create `sim-*` regions | Simulations |
| Config `PUT`, API key create (plaintext shown once) and revoke, audit log entries for everything | Configuration, Application page, Audit log |

Accounts (username / password): `admin / admin123`, `engineer / engineer123`, `viewer / viewer123`.
The demo role cards on the login page need no password. Set `VITE_MOCK_DEMO_MODE=false` to make
`/auth/demo-login` answer 404 and see the "demo mode is off" state.

Mock state lives in memory: a full browser reload resets it (tokens survive, they are stateless).
Client-side navigation keeps it.

## Environment variables

| Variable | Where | Purpose |
|---|---|---|
| `CATCHY_API_URL` | shell env, read by `vite.config.ts` | Target of the `/api` proxy (default `http://localhost:8090`) |
| `VITE_API_BASE` | `.env*` | Prefix for API URLs (default empty = same origin, i.e. the proxy) |
| `VITE_MOCK_API` | `.env*` | `true` serves the in-browser mock instead of the network |
| `VITE_MOCK_DEMO_MODE` | `.env*` | Mock only: `false` makes demo-login return 404 |
| `VITE_MOCK_LATENCY_MS` | `.env*` | Mock only: artificial latency (default 120) |

See `.env.example`.

## Scripts

| Script | What it does |
|---|---|
| `npm run dev` | Dev server on :5173 against the real backend |
| `npm run dev:mock` | Dev server on :5173 with the in-browser mock API |
| `npm run build` | Type-check, then production build into `dist/` |
| `npm run build:mock` | Production build that includes the mock (for static demos) |
| `npm run preview` | Serve `dist/` on :5173 (same `/api` proxy) |
| `npm run typecheck` | `tsc --noEmit` (strict) |
| `npm test` | Vitest in watch mode; use `npm test -- --run` for a single run |

The mock is loaded through a dynamic `import('./mock')` guarded by `import.meta.env.VITE_MOCK_API === 'true'`.
`vite.config.ts` bakes the flag in at build time, so a normal `npm run build` does not even emit the
mock chunk (`dist/assets` contains only the app bundle and CSS).

## Tests

```bash
npm test -- --run
```

Vitest + Testing Library (jsdom). They cover formatting helpers, the eight region sort modes (including risk
order), health thresholds (80/90/98), role helpers and config-form validation, chart components with data
and empty data, the API client (bearer token, 401 handling, error mapping, storage failures), `usePolling`
(no overlap, tab-hidden pause, stale responses), the mock API (runtime shape checks against the TypeScript types,
evolution over time, roles, policy-change flow, config, simulations, keys, audit), the login demo-role flow,
role gating (VIEWER vs ENGINEER vs ADMIN), the Overview (KPIs / loading / empty / error), the Policy Arena
(self-approval disabled for an ENGINEER requester) and the one-time API key reveal dialog.

## Project structure

```
src/
  api/          types.ts (contract), client.ts (fetch + bearer + 401), endpoints.ts (typed api.*),
                session.ts (sessionStorage token, guarded), mock/ (in-browser backend, lazy-loaded)
  charts/       small SVG charts: Donut, TimelineChart, Sparkline, HBars, StackedBars, Meter, ChartFrame (legend + table view)
  components/   Table, Badge(s), Tile, Card, Modal, Toast (context), States (Empty/Error/Skeleton), RegionTable,
                ApplicationCard, EvictionXray, ApiKeysPanel, PolicyRequestsPanel, ConfigPanels, AppShell, ...
  context/      AuthContext (login, demo-login, /auth/me validation, 401 -> login), ToastContext
  hooks/        usePolling (3 s, no overlap, pauses when hidden), useFetch, useTheme, useNow, useGroupSelection
  lib/          format.ts, sort.ts, health.ts, roles.ts, events.ts, configForm.ts, routes.ts, applications.ts
  pages/        Login, Overview, Projects, ProjectDetail, Applications, ApplicationDetail, RegionDetail, Regions,
                PolicyArena, Recommendations, Simulations, AuditLog, Configuration, NotFound
  styles/       tokens.css (light/dark tokens), base.css, ui.css, charts.css, features.css
  test/         fixtures, render helpers, runtime shape checks
```

## Design notes

- **Theme**: CSS variables on `:root`; dark overrides apply under `@media (prefers-color-scheme: dark)` guarded by
  `:root:not([data-theme="light"])` and again under `:root[data-theme="dark"]`. The toggle persists in `localStorage`
  (guarded; the app works if storage throws). System font stack only — no CDN or font requests.
- **Charts** follow the dataviz rules: one y-axis per chart (evictions/expirations are a separate view rather than a
  dual axis), categorical colors in fixed order (hits blue, misses orange, evictions aqua, expirations yellow,
  LRU violet, LFU aqua), 2 px lines, hairline grid, legends with ink-colored text, direct end-labels only when they fit,
  crosshair tooltips that also work from the keyboard, and a **Table view** twin for every chart.
  Status colors always ship with an icon and text (health badges, memory meter severity).
- **Live updates**: pages poll every 3 s; one request at a time, paused while the tab is hidden, with a
  "Live • updated 2 s ago" indicator (state changes are announced through an `aria-live` region).
- **Roles**: nav items and admin pages are hidden/guarded client-side; the backend remains the real enforcement and
  any 403 is shown as a toast. An ENGINEER cannot approve their own request (button disabled with an explanation; ADMIN can).
- **Privacy**: the UI only ever renders fingerprints, counters, sizes and timings. The plaintext API key is shown
  once in a dialog that cannot be dismissed by accident and is dropped from state when closed.

## Notes on the contract

Where `docs/api.md` is silent the dashboard picks the most natural reading (no contract shape is changed):

- `GET /overview` may list only applications that have reported, so the Applications page merges `GET /applications`
  with the overview summaries; an application with no telemetry still appears, as a "No telemetry yet" card.
- An application's `health` is "the worst region health" without saying which region, so the page derives the worst
  region from its region list and names it next to the exact reasons.
- The newest timeline bucket is still filling (a partial interval), so it is hidden until complete to avoid a misleading dip.
- Approve **and** reject are blocked client-side for the requester when they are not an ADMIN (the contract states the
  rule for both; the backend still decides).
- Simulation endpoints do not state a success code: any `2xx` is accepted. `recommendationSummary` and `reasons` are
  displayed verbatim.
- Percentages are rendered with two decimals everywhere (`91.40%`); memory is always labelled as estimated.
