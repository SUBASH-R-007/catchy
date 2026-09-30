/**
 * Opt-in live contract check: validates the REAL telemetry-service responses against the dashboard's runtime shape specs.
 *
 *   CATCHY_LIVE_API=http://localhost:8090 npm test -- --run src/test/liveContract.test.ts
 *
 * Skipped unless CATCHY_LIVE_API is set. Needs the telemetry service running with CATCHY_DEMO_MODE=true and some traffic
 * (run scripts/demo-load.sh first). Creates and immediately revokes one API key and runs one simulation.
 */
import { describe, expect, it } from 'vitest';
import {
  ALERT_SPEC,
  API_KEY_CREATED_SPEC,
  API_KEY_SPEC,
  APPLICATION_SPEC,
  APPLICATION_SUMMARY_SPEC,
  AUDIT_SPEC,
  EVENT_SPEC,
  HEALTH_SHAPE,
  OVERVIEW_SPEC,
  POLICY_REQUEST_SPEC,
  PROJECT_SPEC,
  RECOMMENDATION_SPEC,
  REGION_CONFIG_SPEC,
  REGION_METRICS_SPEC,
  SIMULATION_SPEC,
  TIMELINE_SPEC,
  checkShape,
  type Spec,
} from './shapes';

const BASE = process.env.CATCHY_LIVE_API;
const run = BASE ? describe : describe.skip;

async function call(path: string, token: string, init: RequestInit = {}): Promise<{ status: number; body: unknown }> {
  const res = await fetch(`${BASE}/api/v1${path}`, {
    ...init,
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}`, ...(init.headers ?? {}) },
  });
  const text = await res.text();
  return { status: res.status, body: text ? JSON.parse(text) : null };
}

function expectShape(label: string, value: unknown, spec: Spec): void {
  const problems = checkShape(value, spec);
  expect(problems, `${label}: ${problems.slice(0, 8).join(' | ')}`).toEqual([]);
}

function expectEach(label: string, value: unknown, spec: Spec): void {
  expect(Array.isArray(value), `${label} should be an array`).toBe(true);
  (value as unknown[]).slice(0, 25).forEach((v, i) => expectShape(`${label}[${i}]`, v, spec));
}

run('live telemetry-service matches the dashboard contract', () => {
  it('returns contract-shaped payloads for every read endpoint the UI uses', async () => {
    const login = await fetch(`${BASE}/api/v1/auth/demo-login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ role: 'ADMIN' }),
    });
    expect(login.status).toBe(200);
    const token = ((await login.json()) as { token: string }).token;

    const overview = await call('/overview', token);
    expect(overview.status).toBe(200);
    expectShape('overview', overview.body, OVERVIEW_SPEC);

    const regions = (overview.body as { regions: Array<{ applicationId: number; cacheRegion: string }> }).regions;
    expect(regions.length, 'run scripts/demo-load.sh so there is data').toBeGreaterThan(0);
    expectEach('overview.regions', regions, REGION_METRICS_SPEC);
    expectEach('overview.applications', (overview.body as { applications: unknown }).applications, APPLICATION_SUMMARY_SPEC);
    expectEach('overview.activeAlerts', (overview.body as { activeAlerts: unknown }).activeAlerts, ALERT_SPEC);
    expectEach('overview.latestRecommendations', (overview.body as { latestRecommendations: unknown }).latestRecommendations, RECOMMENDATION_SPEC);

    const projects = await call('/projects', token);
    expectEach('projects', projects.body, PROJECT_SPEC);
    const apps = await call('/applications', token);
    expectEach('applications', apps.body, APPLICATION_SPEC);

    const first = regions.find((r) => !r.cacheRegion.startsWith('sim-')) ?? regions[0];
    const app = first.applicationId;
    const region = first.cacheRegion;

    const appMetrics = await call(`/applications/${app}/metrics`, token);
    expectShape('application metrics', appMetrics.body, APPLICATION_SUMMARY_SPEC);
    expectEach('application metrics.regions', (appMetrics.body as { regions: unknown }).regions, REGION_METRICS_SPEC);

    expectEach('application regions', (await call(`/applications/${app}/regions`, token)).body, REGION_METRICS_SPEC);
    expectShape('region metrics', (await call(`/applications/${app}/regions/${region}/metrics`, token)).body, REGION_METRICS_SPEC);
    expectShape('region health', (await call(`/applications/${app}/regions/${region}/health`, token)).body, { shape: HEALTH_SHAPE });
    expectEach('region events', (await call(`/applications/${app}/regions/${region}/events?limit=50`, token)).body, EVENT_SPEC);
    expectEach('application events', (await call(`/applications/${app}/events?limit=50`, token)).body, EVENT_SPEC);
    expectShape('region timeline', (await call(`/applications/${app}/regions/${region}/timeline?minutes=15&bucketSeconds=10`, token)).body, TIMELINE_SPEC);
    expectShape('application timeline', (await call(`/applications/${app}/timeline?minutes=15&bucketSeconds=10`, token)).body, TIMELINE_SPEC);
    expectShape('global timeline', (await call('/timeline?minutes=15&bucketSeconds=10', token)).body, TIMELINE_SPEC);
    expectEach('recommendations', (await call(`/applications/${app}/recommendations`, token)).body, RECOMMENDATION_SPEC);
    expectEach('policy requests', (await call(`/applications/${app}/policy-change-requests`, token)).body, POLICY_REQUEST_SPEC);
    expectShape('region config', (await call(`/applications/${app}/regions/${region}/config`, token)).body, REGION_CONFIG_SPEC);
    expectEach('audit log', (await call('/audit-logs?limit=50', token)).body, AUDIT_SPEC);
    expectEach('api keys', (await call(`/applications/${app}/api-keys`, token)).body, API_KEY_SPEC);

    const created = await call(`/applications/${app}/api-keys`, token, { method: 'POST', body: JSON.stringify({ label: 'contract-check' }) });
    expect(created.status).toBe(201);
    expectShape('api key created', created.body, API_KEY_CREATED_SPEC);
    const keyId = (created.body as { id: number }).id;
    expect((await call(`/api-keys/${keyId}`, token, { method: 'DELETE' })).status).toBe(204);

    const sim = await call(`/applications/${app}/simulate/policy-comparison`, token, { method: 'POST' });
    expect(sim.status).toBeLessThan(300);
    expectShape('simulation', sim.body, SIMULATION_SPEC);
  }, 60_000);
});
