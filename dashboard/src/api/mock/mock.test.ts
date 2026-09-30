import { beforeEach, describe, expect, it } from 'vitest';
import type {
  ApiKey,
  ApiKeyCreated,
  Application,
  ApplicationMetrics,
  AuditLogEntry,
  CacheEvent,
  LoginResponse,
  Overview,
  PolicyChangeRequest,
  Project,
  Recommendation,
  RegionConfig,
  RegionMetrics,
  Role,
  SimulationResult,
  Timeline,
} from '../types';
import {
  API_KEY_CREATED_SPEC,
  API_KEY_SPEC,
  APPLICATION_SPEC,
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
  APPLICATION_SUMMARY_SPEC,
  checkShape,
} from '../../test/shapes';
import { MockBackend } from './backend';
import { TICK_MS } from './model';

const T0 = Date.parse('2026-09-30T09:00:00Z');

interface Harness {
  backend: MockBackend;
  advance: (ms: number) => void;
  call: <T>(method: string, path: string, body?: unknown, token?: string | null) => { status: number; body: T };
  login: (role: Role) => string;
}

function harness(options: { demoMode?: boolean } = {}): Harness {
  let t = T0;
  const backend = new MockBackend({ seed: 42, now: () => t, preseedTicks: 300, ...options });
  const call = <T,>(method: string, path: string, body?: unknown, token: string | null = null) => {
    const [pathname = '', qs = ''] = path.split('?');
    const res = backend.handle({ method, path: pathname, query: new URLSearchParams(qs), body, token });
    return { status: res.status, body: res.body as T };
  };
  const login = (role: Role) => {
    const res = call<LoginResponse>('POST', '/auth/demo-login', { role });
    return res.body.token;
  };
  return { backend, advance: (ms) => (t += ms), call, login };
}

function expectShape(value: unknown, spec: Parameters<typeof checkShape>[1]): void {
  expect(checkShape(value, spec)).toEqual([]);
}

let h: Harness;
beforeEach(() => {
  h = harness();
});

describe('mock API: contract shapes', () => {
  it('returns a contract-shaped overview', () => {
    const token = h.login('VIEWER');
    const res = h.call<Overview>('GET', '/overview', undefined, token);
    expect(res.status).toBe(200);
    expectShape(res.body, OVERVIEW_SPEC);
    expect(res.body.applicationsMonitored).toBe(3);
    expect(res.body.cacheRegionsMonitored).toBe(6);
    expect(res.body.projectsMonitored).toBe(3);
  });

  it('keeps percentages consistent: hitRate + missRate = 100 and utilization within 0..100', () => {
    const token = h.login('VIEWER');
    const { body } = h.call<Overview>('GET', '/overview', undefined, token);
    for (const r of body.regions) {
      expect(r.hitRate + r.missRate).toBeCloseTo(100, 1);
      expect(r.memoryUtilizationPercent).toBeGreaterThan(0);
      expect(r.memoryUtilizationPercent).toBeLessThanOrEqual(100);
      expect(r.size).toBeLessThanOrEqual(r.capacity);
    }
    expect(body.totals.hitRate + body.totals.missRate).toBeCloseTo(100, 1);
  });

  it('returns application, project, region, timeline, event, recommendation and request shapes', () => {
    const token = h.login('ADMIN');
    const projects = h.call<Project[]>('GET', '/projects', undefined, token).body;
    expect(projects).toHaveLength(3);
    projects.forEach((p) => expectShape(p, PROJECT_SPEC));
    const apps = h.call<Application[]>('GET', '/applications', undefined, token).body;
    apps.forEach((a) => expectShape(a, APPLICATION_SPEC));
    const metrics = h.call<ApplicationMetrics>('GET', '/applications/1/metrics', undefined, token).body;
    expectShape(metrics, APPLICATION_SUMMARY_SPEC);
    metrics.regions.forEach((r) => expectShape(r, REGION_METRICS_SPEC));
    const region = h.call<RegionMetrics>('GET', '/applications/1/regions/claim-rules/metrics', undefined, token).body;
    expectShape(region, REGION_METRICS_SPEC);
    const timeline = h.call<Timeline>('GET', '/applications/1/regions/claim-rules/timeline?minutes=15&bucketSeconds=10', undefined, token).body;
    expectShape(timeline, TIMELINE_SPEC);
    expect(timeline.points).toHaveLength(90);
    expect(timeline.points.every((p) => p.hits >= 0 && p.misses >= 0 && p.evictions >= 0)).toBe(true);
    const events = h.call<CacheEvent[]>('GET', '/applications/1/regions/claim-rules/events?limit=50', undefined, token).body;
    expect(events.length).toBeGreaterThan(10);
    events.forEach((e) => expectShape(e, EVENT_SPEC));
    const recs = h.call<Recommendation[]>('GET', '/applications/2/recommendations', undefined, token).body;
    recs.forEach((r) => expectShape(r, RECOMMENDATION_SPEC));
    const reqs = h.call<PolicyChangeRequest[]>('GET', '/applications/2/policy-change-requests', undefined, token).body;
    reqs.forEach((r) => expectShape(r, POLICY_REQUEST_SPEC));
    const cfg = h.call<RegionConfig>('GET', '/applications/1/regions/claim-rules/config', undefined, token).body;
    expectShape(cfg, REGION_CONFIG_SPEC);
    const audit = h.call<AuditLogEntry[]>('GET', '/audit-logs?limit=500', undefined, token).body;
    audit.forEach((e) => expectShape(e, AUDIT_SPEC));
    const keys = h.call<ApiKey[]>('GET', '/applications/1/api-keys', undefined, token).body;
    keys.forEach((k) => expectShape(k, API_KEY_SPEC));
    expectShape(h.call('GET', '/applications/1/regions/claim-rules/health', undefined, token).body, { shape: HEALTH_SHAPE });
  });

  it('never exposes raw keys: fingerprints match sha256:<hex> or are null', () => {
    const token = h.login('VIEWER');
    const events = h.call<CacheEvent[]>('GET', '/applications/2/events?limit=500', undefined, token).body;
    expect(events.length).toBeGreaterThan(50);
    for (const e of events) {
      if (e.keyFingerprint != null) expect(e.keyFingerprint).toMatch(/^sha256:[0-9a-f]{8,64}$/);
      expect((e.reason ?? '').length).toBeLessThanOrEqual(400);
    }
  });

  it('returns events newest first and honours the actions filter', () => {
    const token = h.login('VIEWER');
    const all = h.call<CacheEvent[]>('GET', '/applications/2/regions/member-eligibility/events?limit=200', undefined, token).body;
    for (let i = 1; i < all.length; i += 1) {
      expect(Date.parse((all[i - 1] as CacheEvent).timestamp)).toBeGreaterThanOrEqual(Date.parse((all[i] as CacheEvent).timestamp));
    }
    const evictions = h.call<CacheEvent[]>(
      'GET',
      '/applications/2/regions/member-eligibility/events?actions=ENTRY_LIMIT_EVICTED,MEMORY_EVICTED,EVICTED&limit=100',
      undefined,
      token,
    ).body;
    expect(evictions.length).toBeGreaterThan(0);
    expect(evictions.every((e) => e.action.includes('EVICTED'))).toBe(true);
  });
});

describe('mock API: demo story', () => {
  it('has one deliberately WARNING region with exact reasons and a CRITICAL-risk region', () => {
    const token = h.login('VIEWER');
    const { body } = h.call<Overview>('GET', '/overview', undefined, token);
    const warning = body.regions.filter((r) => r.health.status === 'WARNING');
    expect(warning.map((r) => r.cacheRegion)).toEqual(['member-eligibility']);
    expect(warning[0]?.health.reasons.length).toBeGreaterThanOrEqual(2);
    expect(warning[0]?.health.reasons.join(' ')).toMatch(/below 65% target/);
    const critical = body.regions.find((r) => r.cacheRegion === 'authorization-decision');
    expect(critical?.riskLevel).toBe('CRITICAL');
    expect(body.activeAlerts.length).toBeGreaterThanOrEqual(2);
    expect(body.activeAlerts.every((a) => a.cacheRegion === 'member-eligibility')).toBe(true);
  });

  it('recommends switching member-eligibility from LRU to LFU and keeping the rest', () => {
    const token = h.login('VIEWER');
    const recs = h.call<Recommendation[]>('GET', '/applications/2/recommendations', undefined, token).body;
    const member = recs.find((r) => r.cacheRegion === 'member-eligibility');
    expect(member).toMatchObject({ action: 'SWITCH', currentPolicy: 'LRU', recommendedPolicy: 'LFU', approvalRequired: true });
    expect(member?.improvementPercent).toBeGreaterThanOrEqual(5);
    expect(member?.aiExplanation).toBeTruthy();
    expect(member?.pendingRequestId).toBe(3);
    const others = h.call<Recommendation[]>('GET', '/applications/1/recommendations', undefined, token).body;
    expect(others.every((r) => r.action === 'KEEP')).toBe(true);
  });

  it('evolves: counters grow on each poll, timeline gets new buckets, application without telemetry stays empty', () => {
    const token = h.login('VIEWER');
    const before = h.call<Overview>('GET', '/overview', undefined, token).body;
    h.advance(TICK_MS * 4);
    const after = h.call<Overview>('GET', '/overview', undefined, token).body;
    expect(after.totals.hits).toBeGreaterThan(before.totals.hits);
    expect(after.totals.misses).toBeGreaterThan(before.totals.misses);
    expect(after.totals.telemetryEventsSent).toBeGreaterThan(before.totals.telemetryEventsSent);
    const app4 = h.call<ApplicationMetrics>('GET', '/applications/4/metrics', undefined, token).body;
    expect(app4.regionCount).toBe(0);
    expect(app4.lastTelemetryAt ?? null).toBeNull();
    expect(app4.policyLabel).toBe('NONE');
    expect(app4.recommendationSummary).toBe('No data yet');
  });

  it('produces an eviction burst on a schedule', () => {
    const token = h.login('VIEWER');
    let maxBucket = 0;
    let total = 0;
    for (let i = 0; i < 120; i += 1) {
      h.advance(TICK_MS);
      const tl = h.call<Timeline>('GET', '/applications/1/regions/claim-rules/timeline?minutes=1&bucketSeconds=5', undefined, token).body;
      const sum = tl.points.reduce((s, p) => s + p.evictions, 0);
      total += sum;
      maxBucket = Math.max(maxBucket, ...tl.points.map((p) => p.evictions));
    }
    expect(total).toBeGreaterThan(0);
    expect(maxBucket).toBeGreaterThan(5);
  });
});

describe('mock API: auth and roles', () => {
  it('supports login with username/password and rejects bad credentials', () => {
    const good = h.call<LoginResponse>('POST', '/auth/login', { username: 'engineer', password: 'engineer123' });
    expect(good.status).toBe(200);
    expect(good.body).toMatchObject({ username: 'engineer', role: 'ENGINEER' });
    const bad = h.call<{ message: string }>('POST', '/auth/login', { username: 'engineer', password: 'nope' });
    expect(bad.status).toBe(401);
    expect(bad.body.message).toMatch(/invalid/i);
    const blank = h.call<{ details: string[] }>('POST', '/auth/login', { username: '', password: '' });
    expect(blank.status).toBe(400);
    expect(blank.body.details).toContain('username: must not be blank');
  });

  it('demo-login returns a usable token for every role and 404s when demo mode is off', () => {
    for (const role of ['ADMIN', 'ENGINEER', 'VIEWER'] as const) {
      const res = h.call<LoginResponse>('POST', '/auth/demo-login', { role });
      expect(res.body.role).toBe(role);
      const me = h.call<{ username: string; role: Role }>('GET', '/auth/me', undefined, res.body.token);
      expect(me.body).toEqual({ username: role.toLowerCase(), role });
    }
    const off = harness({ demoMode: false });
    expect(off.call('POST', '/auth/demo-login', { role: 'ADMIN' }).status).toBe(404);
  });

  it('answers 401 without or with an invalid token and 403 for insufficient roles (audited)', () => {
    expect(h.call('GET', '/overview').status).toBe(401);
    expect(h.call('GET', '/overview', undefined, 'garbage').status).toBe(401);
    const viewer = h.login('VIEWER');
    expect(h.call('GET', '/audit-logs', undefined, viewer).status).toBe(403);
    expect(h.call('POST', '/projects', { name: 'Nope' }, viewer).status).toBe(403);
    expect(h.call('POST', '/applications/1/simulate/sample-workload', undefined, viewer).status).toBe(403);
    const admin = h.login('ADMIN');
    const audit = h.call<AuditLogEntry[]>('GET', '/audit-logs?action=ACCESS_DENIED', undefined, admin).body;
    expect(audit.some((e) => e.actor === 'viewer' && e.outcome === 'DENIED')).toBe(true);
  });

  it('expires tokens', () => {
    let t = T0;
    const b = new MockBackend({ now: () => t, preseedTicks: 5 });
    const login = b.handle({ method: 'POST', path: '/auth/demo-login', body: { role: 'VIEWER' } }).body as LoginResponse;
    expect(b.handle({ method: 'GET', path: '/auth/me', token: login.token }).status).toBe(200);
    t += 9 * 3600_000;
    expect(b.handle({ method: 'GET', path: '/auth/me', token: login.token }).status).toBe(401);
  });
});

describe('mock API: projects, applications and keys (ADMIN)', () => {
  it('creates projects with validation and duplicate detection', () => {
    const admin = h.login('ADMIN');
    const bad = h.call<{ details: string[] }>('POST', '/projects', { name: ' ' }, admin);
    expect(bad.status).toBe(400);
    expect(bad.body.details[0]).toContain('name');
    const ok = h.call<Project>('POST', '/projects', { name: 'Care Management', description: 'New' }, admin);
    expect(ok.status).toBe(201);
    expectShape(ok.body, PROJECT_SPEC);
    expect(h.call('POST', '/projects', { name: 'care management' }, admin).status).toBe(409);
  });

  it('creates applications with regex validation', () => {
    const admin = h.login('ADMIN');
    expect(h.call('POST', '/projects/1/applications', { name: 'Bad Name', environment: 'staging' }, admin).status).toBe(400);
    const ok = h.call<Application>('POST', '/projects/1/applications', { name: 'new-service', displayName: 'New Service', environment: 'dev' }, admin);
    expect(ok.status).toBe(201);
    expect(ok.body.regionCount).toBe(0);
    expect(h.call('POST', '/projects/1/applications', { name: 'new-service', environment: 'dev' }, admin).status).toBe(409);
  });

  it('creates keys (plaintext once, masked afterwards) and revokes them', () => {
    const admin = h.login('ADMIN');
    const created = h.call<ApiKeyCreated>('POST', '/applications/1/api-keys', { label: 'staging pod 2' }, admin);
    expect(created.status).toBe(201);
    expectShape(created.body, API_KEY_CREATED_SPEC);
    expect(created.body.apiKey).toMatch(/^acc_[0-9a-f]{8}_[0-9a-f]{32}$/);
    const list = h.call<ApiKey[]>('GET', '/applications/1/api-keys', undefined, admin).body;
    const row = list.find((k) => k.id === created.body.id);
    expect(row?.active).toBe(true);
    expect(JSON.stringify(list)).not.toContain(created.body.apiKey);
    expect(row?.maskedKey).toContain('••••');
    expect(h.call('DELETE', `/api-keys/${created.body.id}`, undefined, admin).status).toBe(204);
    const after = h.call<ApiKey[]>('GET', '/applications/1/api-keys', undefined, admin).body.find((k) => k.id === created.body.id);
    expect(after?.active).toBe(false);
    expect(after?.revokedAt).toBeTruthy();
    const audit = h.call<AuditLogEntry[]>('GET', '/audit-logs?applicationId=1', undefined, admin).body;
    expect(audit.some((e) => e.action === 'API_KEY_CREATED' && e.details?.includes('staging pod 2'))).toBe(true);
    expect(audit.some((e) => e.action === 'API_KEY_REVOKED')).toBe(true);
    expect(JSON.stringify(audit)).not.toContain(created.body.apiKey);
  });
});

describe('mock API: policy-change workflow', () => {
  it('blocks self-approval for ENGINEER, lets ADMIN approve, and applies the change after the SDK reports it', () => {
    const engineer = h.login('ENGINEER');
    const admin = h.login('ADMIN');
    const created = h.call<PolicyChangeRequest>(
      'POST',
      '/applications/1/policy-change-requests',
      { cacheRegion: 'claim-rules', requestedPolicy: 'LRU', reason: 'try LRU' },
      engineer,
    );
    expect(created.status).toBe(201);
    expect(created.body).toMatchObject({ status: 'PENDING', requestedBy: 'engineer', currentPolicy: 'LFU', requestedPolicy: 'LRU' });
    // duplicates and no-op requests are rejected
    expect(h.call('POST', '/applications/1/policy-change-requests', { cacheRegion: 'claim-rules', requestedPolicy: 'LFU' }, engineer).status).toBe(400); // same as current policy
    expect(h.call('POST', '/applications/1/policy-change-requests', { cacheRegion: 'claim-rules', requestedPolicy: 'LRU' }, engineer).status).toBe(409); // one PENDING per region
    expect(h.call('POST', '/applications/2/policy-change-requests', { cacheRegion: 'member-eligibility', requestedPolicy: 'LFU' }, engineer).status).toBe(409);
    // self approval is denied (403) and audited
    expect(h.call('POST', `/policy-change-requests/${created.body.id}/approve`, {}, engineer).status).toBe(403);
    expect(h.call('POST', `/policy-change-requests/${created.body.id}/reject`, {}, engineer).status).toBe(403);
    const approved = h.call<PolicyChangeRequest>('POST', `/policy-change-requests/${created.body.id}/approve`, { note: 'ok' }, admin);
    expect(approved.status).toBe(200);
    expect(approved.body).toMatchObject({ status: 'APPROVED', decidedBy: 'admin', decisionNote: 'ok' });
    expect(h.call('POST', `/policy-change-requests/${created.body.id}/approve`, {}, admin).status).toBe(409);
    // the "SDK" applies it after a couple of telemetry ticks
    h.advance(TICK_MS * 3);
    const list = h.call<PolicyChangeRequest[]>('GET', '/applications/1/policy-change-requests', undefined, admin).body;
    expect(list[0]).toMatchObject({ id: created.body.id, status: 'APPLIED' });
    expect(list[0]?.appliedAt).toBeTruthy();
    const region = h.call<RegionMetrics>('GET', '/applications/1/regions/claim-rules/metrics', undefined, admin).body;
    expect(region.activePolicy).toBe('LRU');
    const events = h.call<CacheEvent[]>('GET', '/applications/1/regions/claim-rules/events?actions=POLICY_CHANGED', undefined, admin).body;
    expect(events).toHaveLength(1);
    const audit = h.call<AuditLogEntry[]>('GET', '/audit-logs?limit=500', undefined, admin).body.map((e) => e.action);
    expect(audit).toEqual(expect.arrayContaining(['POLICY_CHANGE_REQUESTED', 'POLICY_CHANGE_APPROVED', 'POLICY_CHANGE_APPLIED', 'ACCESS_DENIED']));
    // cooldown now active, so the region is told to keep its policy
    const rec = h.call<Recommendation[]>('GET', '/applications/1/recommendations', undefined, admin).body.find((r) => r.cacheRegion === 'claim-rules');
    expect(rec?.cooldownActive).toBe(true);
    expect(rec?.action).toBe('KEEP');
  });

  it('lets a second engineer approve: the seeded pending request is decided by ENGINEER and improves member-eligibility', () => {
    const engineer = h.login('ENGINEER');
    const before = h.call<RegionMetrics>('GET', '/applications/2/regions/member-eligibility/metrics', undefined, engineer).body;
    expect(before.activePolicy).toBe('LRU');
    expect(h.call('POST', '/policy-change-requests/3/approve', { note: 'Shadow gain is stable.' }, engineer).status).toBe(200);
    h.advance(TICK_MS * 60);
    const after = h.call<RegionMetrics>('GET', '/applications/2/regions/member-eligibility/metrics', undefined, engineer).body;
    expect(after.activePolicy).toBe('LFU');
    const tl = h.call<Timeline>('GET', '/applications/2/regions/member-eligibility/timeline?minutes=3&bucketSeconds=30', undefined, engineer).body;
    expect(tl.points[tl.points.length - 2]?.hitRate).toBeGreaterThan(before.hitRate);
  });

  it('rejects requests and validates input', () => {
    const engineer = h.login('ENGINEER');
    const admin = h.login('ADMIN');
    expect(h.call('POST', '/applications/1/policy-change-requests', { cacheRegion: 'nope', requestedPolicy: 'LRU' }, engineer).status).toBe(404);
    expect(h.call('POST', '/applications/1/policy-change-requests', { cacheRegion: 'claim-rules', requestedPolicy: 'FIFO' }, engineer).status).toBe(400);
    const rejected = h.call<PolicyChangeRequest>('POST', '/policy-change-requests/3/reject', { note: 'no' }, admin);
    expect(rejected.body.status).toBe('REJECTED');
    const evaluate = h.call<Recommendation[]>('POST', '/applications/2/recommendations/evaluate', undefined, engineer);
    expect(evaluate.status).toBe(200);
    expect(evaluate.body.find((r) => r.cacheRegion === 'member-eligibility')?.pendingRequestId ?? null).toBeNull();
  });
});

describe('mock API: region configuration', () => {
  it('validates bounds, reports pending until applied, then clears', () => {
    const admin = h.login('ADMIN');
    const engineer = h.login('ENGINEER');
    const path = '/applications/1/regions/claim-rules/config';
    expect(h.call('PUT', path, { maximumEntries: 800 }, engineer).status).toBe(403);
    expect(h.call('PUT', path, {}, admin).status).toBe(400);
    const bad = h.call<{ details: string[] }>('PUT', path, { maximumEntries: 0, maximumMemoryBytes: 10, defaultTtlMs: 5 }, admin);
    expect(bad.status).toBe(400);
    expect(bad.body.details).toHaveLength(3);
    const put = h.call<RegionConfig>('PUT', path, { maximumEntries: 60000, defaultTtlMs: 600000, reason: 'month-end' }, admin);
    expect(put.status).toBe(200);
    expectShape(put.body, REGION_CONFIG_SPEC);
    expect(put.body).toMatchObject({ pending: true, tuningVersion: 1, updatedBy: 'admin' });
    expect(put.body.desired).toMatchObject({ maximumEntries: 60000, defaultTtlMs: 600000, maximumMemoryBytes: null });
    h.advance(TICK_MS * 4);
    const after = h.call<RegionConfig>('GET', path, undefined, admin).body;
    expect(after.pending).toBe(false);
    expect(after.reported).toMatchObject({ maximumEntries: 60000, defaultTtlMs: 600000 });
    const events = h.call<CacheEvent[]>('GET', '/applications/1/regions/claim-rules/events?actions=CONFIG_CHANGED', undefined, admin).body;
    expect(events).toHaveLength(1);
    const audit = h.call<AuditLogEntry[]>('GET', '/audit-logs?limit=500', undefined, admin).body.map((e) => e.action);
    expect(audit).toEqual(expect.arrayContaining(['CONFIG_CHANGE_REQUESTED', 'CONFIG_CHANGE_APPLIED']));
  });
});

describe('mock API: simulations', () => {
  it('validates the request bound and kind', () => {
    const engineer = h.login('ENGINEER');
    expect(h.call('POST', '/applications/1/simulate/sample-workload', { requests: 10 }, engineer).status).toBe(400);
    expect(h.call('POST', '/applications/1/simulate/sample-workload', { requests: 20001 }, engineer).status).toBe(400);
    expect(h.call('POST', '/applications/1/simulate/nonsense', undefined, engineer).status).toBe(404);
  });

  it('runs all four simulations and creates sim-* regions', () => {
    const engineer = h.login('ENGINEER');
    const kinds = [
      ['sample-workload', ['sim-sample-workload']],
      ['high-load', ['sim-high-load']],
      ['ttl-expiration', ['sim-ttl-expiration']],
      ['policy-comparison', ['sim-policy-lru', 'sim-policy-lfu']],
    ] as const;
    for (const [kind, regions] of kinds) {
      const res = h.call<SimulationResult>('POST', `/applications/3/simulate/${kind}`, { requests: 800 }, engineer);
      expect(res.status).toBe(200);
      expectShape(res.body, SIMULATION_SPEC);
      expect(res.body.cacheRegions).toEqual(regions);
      expect(res.body.steps.length).toBeGreaterThan(0);
      expect(res.body.hits + res.body.misses).toBeGreaterThan(0);
      expect(Boolean(res.body.comparison)).toBe(kind === 'policy-comparison');
    }
    const app = h.call<Application>('GET', '/applications/3', undefined, engineer).body;
    expect(app.regionCount).toBe(2 + 5);
  });

  it('ttl-expiration yields MISS and EXPIRED events; high-load yields entry- and memory-limit evictions', () => {
    const engineer = h.login('ENGINEER');
    h.call('POST', '/applications/1/simulate/ttl-expiration', undefined, engineer);
    const ttlEvents = h.call<CacheEvent[]>('GET', '/applications/1/regions/sim-ttl-expiration/events?limit=500', undefined, engineer).body;
    expect(ttlEvents.some((e) => e.action === 'MISS')).toBe(true);
    expect(ttlEvents.some((e) => e.action === 'EXPIRED')).toBe(true);
    const high = h.call<SimulationResult>('POST', '/applications/1/simulate/high-load', { requests: 50 }, engineer).body;
    expect(high.evictions).toBeGreaterThan(0);
    const events = h.call<CacheEvent[]>('GET', '/applications/1/regions/sim-high-load/events?limit=500', undefined, engineer).body;
    expect(events.some((e) => e.action === 'ENTRY_LIMIT_EVICTED')).toBe(true);
    expect(events.some((e) => e.action === 'MEMORY_EVICTED')).toBe(true);
  });

  it('policy-comparison uses real LRU/LFU caches: LRU wins the moving set, LFU wins stable popularity', () => {
    const engineer = h.login('ENGINEER');
    const res = h.call<SimulationResult>('POST', '/applications/1/simulate/policy-comparison', { requests: 2000 }, engineer).body;
    const [a, b] = res.comparison ?? [];
    expect(a).toMatchObject({ winner: 'LRU' });
    expect(a?.lruHitRate).toBeGreaterThan(a?.lfuHitRate ?? 100);
    expect(b).toMatchObject({ winner: 'LFU' });
    expect(b?.lfuHitRate).toBeGreaterThan(b?.lruHitRate ?? 100);
    const audit = h.call<AuditLogEntry[]>('GET', '/audit-logs?action=SIMULATION_RUN', undefined, h.login('ADMIN')).body;
    expect(audit.length).toBeGreaterThan(1);
  });
});
