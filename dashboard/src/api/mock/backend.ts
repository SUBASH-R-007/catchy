/**
 * In-browser implementation of the CATCHY Telemetry Service API (docs/api.md), used when
 * VITE_MOCK_API=true and in tests. It is stateful and evolves over time:
 * every 3 s of wall-clock time one telemetry "tick" is applied to every demo region.
 */
import type {
  Alert,
  ApiErrorBody,
  ApiKey,
  ApiKeyCreated,
  Application,
  ApplicationMetrics,
  ApplicationSummary,
  AuditAction,
  AuditLogEntry,
  AuditOutcome,
  CacheAction,
  CacheEvent,
  EvictionPolicy,
  LoginResponse,
  Overview,
  PolicyChangeRequest,
  Project,
  ProjectMetrics,
  Recommendation,
  RegionConfig,
  RegionMetrics,
  Role,
  SimulationKind,
  UpdateRegionConfigRequest,
} from '../types';
import {
  AUDIT_ACTIONS,
  CACHE_ACTIONS,
  CONFIG_BOUNDS,
  EVICTION_POLICIES,
  ROLES,
  SIMULATION_KINDS,
  SIMULATION_REQUEST_BOUNDS,
} from '../types';
import { healthRank, worstHealth } from '../../lib/health';
import { hasRole } from '../../lib/roles';
import { formatBytes, formatDuration, formatInt } from '../../lib/format';
import {
  buildEvent,
  buildTimeline,
  computeRecommendation,
  createRegion,
  healthOfRegion,
  pushEvent,
  regionMetrics,
  sumTotals,
  tickRegion,
  totalsOfRegion,
  type EngineContext,
} from './engine';
import {
  APPLY_DELAY_TICKS,
  DEMO_APPS,
  DEMO_PROJECTS,
  TICK_MS,
  type ApiKeyRecord,
  type AppRecord,
  type ProjectRecord,
  type RegionState,
} from './model';
import { Rng } from './prng';
import { runSimulation } from './simulate';

export interface MockOptions {
  seed?: number;
  /** Clock in epoch ms (injectable for tests). */
  now?: () => number;
  /** When false `/auth/demo-login` answers 404, like a backend with CATCHY_DEMO_MODE=false. */
  demoMode?: boolean;
  /** How many historical ticks to pre-generate so charts are populated at first load. */
  preseedTicks?: number;
}

export interface MockRequest {
  method: string;
  /** Path below `/api/v1`, e.g. `/applications/1/metrics`. */
  path: string;
  query?: URLSearchParams;
  body?: unknown;
  token?: string | null;
}

export interface MockResponse {
  status: number;
  body?: unknown;
}

interface AuthUser {
  username: string;
  role: Role;
}

class HttpError extends Error {
  constructor(
    readonly status: number,
    message: string,
    readonly details: string[] = [],
  ) {
    super(message);
  }
}

const STATUS_TEXT: Record<number, string> = {
  400: 'Bad Request',
  401: 'Unauthorized',
  403: 'Forbidden',
  404: 'Not Found',
  409: 'Conflict',
  500: 'Internal Server Error',
};

interface RouteContext {
  method: string;
  path: string;
  query: URLSearchParams;
  body: unknown;
  params: string[];
  user: AuthUser | null;
}

type Handler = (c: RouteContext) => MockResponse;

interface Route {
  method: string;
  pattern: RegExp;
  /** `null` = public. */
  minRole: Role | null;
  handler: Handler;
}

const USERS: Record<string, { password: string; role: Role }> = {
  admin: { password: 'admin123', role: 'ADMIN' },
  engineer: { password: 'engineer123', role: 'ENGINEER' },
  viewer: { password: 'viewer123', role: 'VIEWER' },
};

const TOKEN_TTL_MS = 8 * 60 * 60 * 1000;

const iso = (ms: number) => new Date(ms).toISOString();
const ok = (body: unknown): MockResponse => ({ status: 200, body });
const created = (body: unknown): MockResponse => ({ status: 201, body });

function asRecord(body: unknown): Record<string, unknown> {
  return body && typeof body === 'object' && !Array.isArray(body) ? (body as Record<string, unknown>) : {};
}

function intParam(q: URLSearchParams, name: string, def: number, min: number, max: number): number {
  const raw = q.get(name);
  if (raw === null || raw === '') return def;
  const n = Number(raw);
  if (!Number.isInteger(n) || n < min || n > max) {
    throw new HttpError(400, 'Validation failed', [`${name}: must be between ${min} and ${max}`]);
  }
  return n;
}

export class MockBackend {
  readonly demoMode: boolean;
  private readonly clock: () => number;
  private readonly rng: Rng;
  private projects: ProjectRecord[] = [];
  private apps: AppRecord[] = [];
  private apiKeys: ApiKeyRecord[] = [];
  private requests: PolicyChangeRequest[] = [];
  private audit: AuditLogEntry[] = [];
  private recMeta = new Map<string, { id: number; signature: string; createdAt: number }>();
  private alertSince = new Map<string, number>();
  private routes: Route[] = [];
  private lastTickAt: number;
  private eventSeq = 0;
  private ids = { project: 0, app: 0, key: 0, request: 0, audit: 0, rec: 0, sim: 0 };

  constructor(options: MockOptions = {}) {
    this.clock = options.now ?? (() => Date.now());
    this.rng = new Rng(options.seed ?? 20260930);
    this.demoMode = options.demoMode ?? true;
    const start = this.clock();
    this.lastTickAt = start;
    this.seedCatalog(start);
    const preseed = options.preseedTicks ?? 600;
    for (let i = preseed; i >= 1; i -= 1) this.tickAll(start - (i - 1) * TICK_MS);
    this.seedHistory(start);
    this.registerRoutes();
  }

  // -------------------------------------------------------------------------
  // Public API
  // -------------------------------------------------------------------------

  /** Answer one request the way the real service would (status + JSON body). */
  handle(req: MockRequest): MockResponse {
    const path = req.path.replace(/\/+$/, '') || '/';
    try {
      this.advance();
      const route = this.routes.find((r) => r.method === req.method && r.pattern.test(path));
      if (!route) {
        const methodKnown = this.routes.some((r) => r.pattern.test(path));
        throw new HttpError(methodKnown ? 405 : 404, methodKnown ? 'Method not allowed' : 'Not found');
      }
      let user: AuthUser | null = null;
      if (route.minRole !== null) {
        user = this.authenticate(req.token ?? null);
        if (!user) throw new HttpError(401, 'Authentication required or token is invalid or expired');
        if (!hasRole(user.role, route.minRole)) {
          this.addAudit(user, 'ACCESS_DENIED', 'ENDPOINT', path, null, `${req.method} ${path} requires ${route.minRole}`, 'DENIED');
          throw new HttpError(403, `This action requires the ${route.minRole} role`);
        }
      }
      const match = route.pattern.exec(path);
      const params = (match ? match.slice(1) : []).map((p) => decodeURIComponent(p));
      return route.handler({
        method: req.method,
        path,
        query: req.query ?? new URLSearchParams(),
        body: req.body,
        params,
        user,
      });
    } catch (e) {
      if (e instanceof HttpError) {
        const body: ApiErrorBody = {
          timestamp: iso(this.clock()),
          status: e.status,
          error: STATUS_TEXT[e.status] ?? 'Error',
          message: e.message,
          path: `/api/v1${req.path}`,
          details: e.details,
        };
        return { status: e.status, body };
      }
      const body: ApiErrorBody = {
        timestamp: iso(this.clock()),
        status: 500,
        error: 'Internal Server Error',
        message: 'Unexpected error',
        path: `/api/v1${req.path}`,
        details: [],
      };
      return { status: 500, body };
    }
  }

  /** Apply `n` telemetry ticks immediately (tests / demos), independent of the wall clock. */
  tick(n = 1): void {
    for (let i = 0; i < n; i += 1) {
      this.lastTickAt += TICK_MS;
      this.tickAll(this.lastTickAt);
    }
  }

  // -------------------------------------------------------------------------
  // Seeding
  // -------------------------------------------------------------------------

  private seedCatalog(start: number): void {
    const createdAt = start - 14 * 24 * 3600_000;
    for (const p of DEMO_PROJECTS) {
      this.projects.push({ ...p, createdAt });
      this.ids.project = Math.max(this.ids.project, p.id);
    }
    for (const a of DEMO_APPS) {
      const regions = new Map<string, RegionState>();
      for (const seed of a.regions) regions.set(seed.name, createRegion(a.id, seed, start));
      this.apps.push({
        id: a.id,
        projectId: a.projectId,
        name: a.name,
        displayName: a.displayName,
        environment: a.environment,
        description: a.description,
        createdAt,
        regions,
      });
      this.ids.app = Math.max(this.ids.app, a.id);
    }
    this.apiKeys = [
      { id: 1, applicationId: 1, label: 'staging pod 1', keyPrefix: 'acc_3f9a1c02', createdAt: start - 13 * 86400_000, lastUsedAt: start - 2000, revokedAt: null },
      { id: 2, applicationId: 1, label: 'old laptop test', keyPrefix: 'acc_77be40d1', createdAt: start - 12 * 86400_000, lastUsedAt: start - 9 * 86400_000, revokedAt: start - 8 * 86400_000 },
      { id: 3, applicationId: 2, label: 'staging pod 1', keyPrefix: 'acc_c41d09aa', createdAt: start - 10 * 86400_000, lastUsedAt: start - 3000, revokedAt: null },
      { id: 4, applicationId: 3, label: 'staging pod 1', keyPrefix: 'acc_8e02b7f5', createdAt: start - 9 * 86400_000, lastUsedAt: start - 1000, revokedAt: null },
    ];
    this.ids.key = 4;
  }

  /** Historic audit entries and policy-change requests so the audit/arena pages are not empty. */
  private seedHistory(start: number): void {
    const admin: AuthUser = { username: 'admin', role: 'ADMIN' };
    const engineer: AuthUser = { username: 'engineer', role: 'ENGINEER' };
    const viewer: AuthUser = { username: 'viewer', role: 'VIEWER' };
    const h = 3600_000;
    const d = 24 * h;
    const at = (ms: number) => start - ms;
    this.addAudit(admin, 'LOGIN_SUCCESS', 'USER', 'admin', null, 'Login succeeded', 'SUCCESS', at(13 * d));
    for (const p of this.projects) {
      this.addAudit(admin, 'PROJECT_CREATED', 'PROJECT', String(p.id), null, `Created project '${p.name}'`, 'SUCCESS', at(13 * d - p.id * 1000));
    }
    for (const a of this.apps) {
      this.addAudit(admin, 'APPLICATION_CREATED', 'APPLICATION', String(a.id), a.id, `Created application '${a.name}' (${a.environment})`, 'SUCCESS', at(12 * d - a.id * 1000));
    }
    for (const k of this.apiKeys) {
      this.addAudit(admin, 'API_KEY_CREATED', 'API_KEY', String(k.id), k.applicationId, `Created key '${k.label}' (${k.keyPrefix})`, 'SUCCESS', k.createdAt);
    }
    this.addAudit(admin, 'API_KEY_REVOKED', 'API_KEY', '2', 1, "Revoked key 'old laptop test' (acc_77be40d1)", 'SUCCESS', at(8 * d));
    this.addAudit({ username: 'unknown', role: 'VIEWER' }, 'LOGIN_FAILURE', 'USER', 'unknown', null, 'Login failed for user', 'FAILURE', at(2 * d));
    this.addAudit(viewer, 'ACCESS_DENIED', 'ENDPOINT', '/projects', null, 'POST /projects requires ADMIN', 'DENIED', at(30 * h));
    this.addAudit(
      { username: 'claims-service', role: 'VIEWER' },
      'TELEMETRY_REJECTED',
      'APPLICATION',
      '1',
      1,
      'Rejected unknown field(s): rawKey',
      'DENIED',
      at(26 * h),
    );

    const claimLookup = this.apps[0]?.regions.get('claim-lookup');
    if (claimLookup) claimLookup.lastPolicyChangeAt = at(2 * d);
    this.requests.push(
      {
        id: 1,
        applicationId: 1,
        applicationName: 'claims-service',
        cacheRegion: 'claim-lookup',
        currentPolicy: 'LFU',
        requestedPolicy: 'LRU',
        reason: 'Policy Arena recommends LRU (+6.2 pts).',
        status: 'APPLIED',
        requestedBy: 'engineer',
        decidedBy: 'admin',
        decisionNote: 'Shadow gain is stable across two days.',
        createdAt: iso(at(2 * d + 2 * h)),
        decidedAt: iso(at(2 * d + h)),
        appliedAt: iso(at(2 * d)),
        recommendationId: null,
      },
      {
        id: 2,
        applicationId: 3,
        applicationName: 'authorization-service',
        cacheRegion: 'prior-auth-rules',
        currentPolicy: 'LRU',
        requestedPolicy: 'LFU',
        reason: 'Trial LFU for month-end volume.',
        status: 'REJECTED',
        requestedBy: 'engineer',
        decidedBy: 'admin',
        decisionNote: 'LRU and LFU are within 1 point; not worth the risk.',
        createdAt: iso(at(30 * h)),
        decidedAt: iso(at(29 * h)),
        appliedAt: null,
        recommendationId: null,
      },
      {
        id: 3,
        applicationId: 2,
        applicationName: 'eligibility-service',
        cacheRegion: 'member-eligibility',
        currentPolicy: 'LRU',
        requestedPolicy: 'LFU',
        reason: 'Policy Arena recommends LFU (+12.8 pts).',
        status: 'PENDING',
        requestedBy: 'admin',
        decidedBy: null,
        decisionNote: null,
        createdAt: iso(at(12 * 60_000)),
        decidedAt: null,
        appliedAt: null,
        recommendationId: null,
      },
    );
    this.ids.request = 3;
    this.addAudit(engineer, 'POLICY_CHANGE_REQUESTED', 'POLICY_CHANGE_REQUEST', '1', 1, "Requested LRU for 'claim-lookup'", 'SUCCESS', at(2 * d + 2 * h));
    this.addAudit(admin, 'POLICY_CHANGE_APPROVED', 'POLICY_CHANGE_REQUEST', '1', 1, "Approved request #1 for 'claim-lookup'", 'SUCCESS', at(2 * d + h));
    this.addAudit(admin, 'POLICY_CHANGE_APPLIED', 'POLICY_CHANGE_REQUEST', '1', 1, "Region 'claim-lookup' now runs LRU", 'SUCCESS', at(2 * d));
    this.addAudit(engineer, 'POLICY_CHANGE_REQUESTED', 'POLICY_CHANGE_REQUEST', '2', 3, "Requested LFU for 'prior-auth-rules'", 'SUCCESS', at(30 * h));
    this.addAudit(admin, 'POLICY_CHANGE_REJECTED', 'POLICY_CHANGE_REQUEST', '2', 3, "Rejected request #2 for 'prior-auth-rules'", 'SUCCESS', at(29 * h));
    this.addAudit(admin, 'POLICY_CHANGE_REQUESTED', 'POLICY_CHANGE_REQUEST', '3', 2, "Requested LFU for 'member-eligibility'", 'SUCCESS', at(12 * 60_000));
    this.addAudit(engineer, 'RECOMMENDATION_EVALUATED', 'APPLICATION', '2', 2, 'Re-evaluated recommendations', 'SUCCESS', at(20 * 60_000));
    this.addAudit(engineer, 'SIMULATION_RUN', 'APPLICATION', '1', 1, 'Ran policy-comparison simulation', 'SUCCESS', at(3 * h));
  }

  // -------------------------------------------------------------------------
  // Time evolution
  // -------------------------------------------------------------------------

  private advance(): void {
    const now = this.clock();
    const maxGap = 120 * TICK_MS;
    if (now - this.lastTickAt > maxGap) this.lastTickAt = now - maxGap;
    while (this.lastTickAt + TICK_MS <= now) {
      this.lastTickAt += TICK_MS;
      this.tickAll(this.lastTickAt);
    }
  }

  private ctxFor(app: AppRecord): EngineContext {
    return { rng: this.rng, nextEventId: () => (this.eventSeq += 1), app };
  }

  private tickAll(t: number): void {
    for (const app of this.apps) {
      const ctx = this.ctxFor(app);
      for (const r of app.regions.values()) {
        if (r.simulation) continue;
        tickRegion(r, ctx, t);
        if (r.pendingPolicy && r.tickCount >= r.pendingPolicy.applyAtTick) this.applyPolicy(app, r, t);
        if (r.pendingConfigApplyAtTick !== null && r.tickCount >= r.pendingConfigApplyAtTick) this.applyConfig(app, r, t);
      }
    }
  }

  private applyPolicy(app: AppRecord, r: RegionState, t: number): void {
    const pending = r.pendingPolicy;
    if (!pending) return;
    const from = r.policy;
    r.policy = pending.policy;
    r.lastPolicyChangeAt = t;
    r.pendingPolicy = null;
    const req = this.requests.find((x) => x.id === pending.requestId);
    if (req) {
      req.status = 'APPLIED';
      req.appliedAt = iso(t);
    }
    this.addAudit(
      { username: 'telemetry-service', role: 'ADMIN' },
      'POLICY_CHANGE_APPLIED',
      'POLICY_CHANGE_REQUEST',
      String(pending.requestId),
      app.id,
      `Region '${r.name}' now runs ${r.policy} (request #${pending.requestId})`,
      'SUCCESS',
      t,
    );
    pushEvent(
      r,
      buildEvent(r, this.ctxFor(app), 'POLICY_CHANGED', {
        reason: `Eviction policy changed from ${from} to ${r.policy} (approved request #${pending.requestId}).`,
        fingerprint: null,
        sizeBefore: r.size,
        sizeAfter: r.size,
        memBefore: r.memoryBytes,
        memAfter: r.memoryBytes,
        valueReturned: false,
        latencyMs: 0,
        timestamp: t,
      }),
    );
  }

  private applyConfig(app: AppRecord, r: RegionState, t: number): void {
    r.pendingConfigApplyAtTick = null;
    const d = r.desired;
    if (!d) return;
    const changes: string[] = [];
    if (typeof d.maximumEntries === 'number' && d.maximumEntries !== r.capacity) {
      changes.push(`maximumEntries ${formatInt(r.capacity)} → ${formatInt(d.maximumEntries)}`);
      const shrink = r.size - d.maximumEntries;
      r.capacity = d.maximumEntries;
      if (shrink > 0) {
        r.size = d.maximumEntries;
        r.counters.evictions += shrink;
        if (r.policy === 'LRU') r.counters.lruEvictions += shrink;
        else r.counters.lfuEvictions += shrink;
        pushEvent(
          r,
          buildEvent(r, this.ctxFor(app), 'EVICTED', {
            severity: 'WARN',
            reason: `Capacity reduced by configuration to ${formatInt(d.maximumEntries)}; ${formatInt(shrink)} entries were evicted.`,
            fingerprint: null,
            sizeBefore: r.size + shrink,
            sizeAfter: r.size,
            memBefore: r.memoryBytes,
            memAfter: r.memoryBytes,
            valueReturned: false,
            latencyMs: 0,
            timestamp: t,
          }),
        );
      }
    }
    if (typeof d.maximumMemoryBytes === 'number' && d.maximumMemoryBytes !== r.maxMemoryBytes) {
      changes.push(`maximumMemory ${formatBytes(r.maxMemoryBytes)} → ${formatBytes(d.maximumMemoryBytes)}`);
      r.maxMemoryBytes = d.maximumMemoryBytes;
    }
    if (typeof d.defaultTtlMs === 'number' && d.defaultTtlMs !== r.defaultTtlMs) {
      changes.push(`defaultTtl ${formatDuration(r.defaultTtlMs)} → ${formatDuration(d.defaultTtlMs)}`);
      r.defaultTtlMs = d.defaultTtlMs;
    }
    if (changes.length === 0) return;
    this.addAudit(
      { username: 'telemetry-service', role: 'ADMIN' },
      'CONFIG_CHANGE_APPLIED',
      'REGION',
      r.name,
      app.id,
      `Applied configuration to '${r.name}': ${changes.join(', ')}`,
      'SUCCESS',
      t,
    );
    pushEvent(
      r,
      buildEvent(r, this.ctxFor(app), 'CONFIG_CHANGED', {
        reason: `Configuration applied: ${changes.join(', ')}.`,
        fingerprint: null,
        sizeBefore: r.size,
        sizeAfter: r.size,
        memBefore: r.memoryBytes,
        memAfter: r.memoryBytes,
        valueReturned: false,
        latencyMs: 0,
        timestamp: t,
      }),
    );
  }

  // -------------------------------------------------------------------------
  // Auth & audit
  // -------------------------------------------------------------------------

  private authenticate(token: string | null): AuthUser | null {
    if (!token) return null;
    const parts = token.split('.');
    if (parts.length !== 4 || parts[0] !== 'mock') return null;
    const role = parts[1] as Role;
    const expires = Number(parts[3]);
    if (!ROLES.includes(role) || !parts[2] || !Number.isFinite(expires) || expires < this.clock()) return null;
    return { username: parts[2], role };
  }

  private issueToken(username: string, role: Role): LoginResponse {
    const expires = this.clock() + TOKEN_TTL_MS;
    return { token: `mock.${role}.${username}.${expires}`, username, role, expiresAt: iso(expires) };
  }

  private addAudit(
    actor: AuthUser,
    action: AuditAction,
    targetType: string,
    targetId: string,
    applicationId: number | null,
    details: string,
    outcome: AuditOutcome,
    at: number = this.clock(),
  ): void {
    this.ids.audit += 1;
    this.audit.push({
      id: this.ids.audit,
      timestamp: iso(at),
      actor: actor.username,
      actorRole: actor.role,
      action,
      targetType,
      targetId,
      applicationId,
      details,
      outcome,
    });
  }

  // -------------------------------------------------------------------------
  // Lookups & builders
  // -------------------------------------------------------------------------

  private app(id: string): AppRecord {
    const app = this.apps.find((a) => String(a.id) === id);
    if (!app) throw new HttpError(404, `Application ${id} not found`);
    return app;
  }

  private region(app: AppRecord, name: string): RegionState {
    const r = app.regions.get(name);
    if (!r) throw new HttpError(404, `Cache region '${name}' not found in application ${app.name}`);
    return r;
  }

  private project(id: string): ProjectRecord {
    const p = this.projects.find((x) => String(x.id) === id);
    if (!p) throw new HttpError(404, `Project ${id} not found`);
    return p;
  }

  private toProject(p: ProjectRecord): Project {
    return {
      id: p.id,
      name: p.name,
      description: p.description,
      createdAt: iso(p.createdAt),
      applicationCount: this.apps.filter((a) => a.projectId === p.id).length,
    };
  }

  private toApplication(a: AppRecord): Application {
    const project = this.projects.find((p) => p.id === a.projectId);
    const last = this.lastTelemetry(a);
    return {
      id: a.id,
      projectId: a.projectId,
      projectName: project?.name ?? 'Unknown project',
      name: a.name,
      displayName: a.displayName,
      environment: a.environment,
      description: a.description,
      createdAt: iso(a.createdAt),
      lastTelemetryAt: last === null ? null : iso(last),
      regionCount: a.regions.size,
    };
  }

  private lastTelemetry(a: AppRecord): number | null {
    let last: number | null = null;
    for (const r of a.regions.values()) if (last === null || r.lastUpdated > last) last = r.lastUpdated;
    return last;
  }

  private regionList(a: AppRecord): RegionState[] {
    return [...a.regions.values()].sort((x, y) => x.name.localeCompare(y.name));
  }

  private metricsOf(a: AppRecord): RegionMetrics[] {
    const now = this.clock();
    return this.regionList(a).map((r) => regionMetrics(r, a, now));
  }

  private recommendationFor(a: AppRecord, r: RegionState, forceNew = false): Recommendation | null {
    const now = this.clock();
    const pending = this.requests.find(
      (x) => x.applicationId === a.id && x.cacheRegion === r.name && x.status === 'PENDING',
    );
    const key = `${a.id}:${r.name}`;
    const probe = computeRecommendation(a, r, now, 0, now, pending?.id ?? null);
    if (!probe) return null;
    const signature = `${probe.action}:${probe.recommendedPolicy}:${probe.currentPolicy}`;
    let meta = this.recMeta.get(key);
    if (!meta || meta.signature !== signature || forceNew) {
      this.ids.rec += 1;
      meta = { id: this.ids.rec, signature, createdAt: now };
      this.recMeta.set(key, meta);
    }
    return { ...probe, id: meta.id, createdAt: iso(meta.createdAt) };
  }

  private recommendationsOf(a: AppRecord, forceNew = false): Recommendation[] {
    const out: Recommendation[] = [];
    for (const r of this.regionList(a)) {
      const rec = this.recommendationFor(a, r, forceNew);
      if (rec) out.push(rec);
    }
    return out;
  }

  private summaryOf(a: AppRecord): ApplicationSummary {
    const now = this.clock();
    const project = this.projects.find((p) => p.id === a.projectId);
    const metrics = this.metricsOf(a);
    const last = this.lastTelemetry(a);
    const base = {
      applicationId: a.id,
      projectId: a.projectId,
      projectName: project?.name ?? 'Unknown project',
      name: a.name,
      displayName: a.displayName,
      environment: a.environment,
      regionCount: metrics.length,
    };
    if (metrics.length === 0) {
      return {
        ...base,
        totals: sumTotals([]),
        health: { status: 'UNKNOWN', score: 0, reasons: ['No telemetry received yet.'] },
        activePolicies: [],
        policyLabel: 'NONE',
        recommendationSummary: 'No data yet',
        lastTelemetryAt: null,
        secondsSinceLastTelemetry: null,
      };
    }
    const worst = metrics.reduce((w, m) =>
      healthRank(m.health.status) > healthRank(w.health.status) ||
      (healthRank(m.health.status) === healthRank(w.health.status) && m.health.score < w.health.score)
        ? m
        : w,
    );
    const policies = [...new Set(metrics.map((m) => m.activePolicy))].sort() as EvictionPolicy[];
    const policyLabel = policies.length === 1 ? (policies[0] as EvictionPolicy) : 'MIXED';
    const recs = this.recommendationsOf(a);
    const sw = recs.find((r) => r.action === 'SWITCH');
    const recommendationSummary = sw
      ? `Switch ${sw.cacheRegion} to ${sw.recommendedPolicy}`
      : policyLabel === 'MIXED'
        ? 'Keep current policies'
        : `Keep ${policyLabel}`;
    return {
      ...base,
      totals: sumTotals(metrics),
      health: { ...worst.health, status: worstHealth(metrics.map((m) => m.health.status)) },
      activePolicies: policies,
      policyLabel,
      recommendationSummary,
      lastTelemetryAt: last === null ? null : iso(last),
      secondsSinceLastTelemetry: last === null ? null : Math.max(0, Math.round((now - last) / 1000)),
    };
  }

  private alerts(): Alert[] {
    const now = this.clock();
    const seen = new Set<string>();
    const out: Alert[] = [];
    for (const app of this.apps) {
      for (const r of this.regionList(app)) {
        if (r.simulation) continue;
        for (const f of healthOfRegion(r, now).failing) {
          const id = `${app.id}:${r.name}:${f.code}`;
          seen.add(id);
          if (!this.alertSince.has(id)) this.alertSince.set(id, now);
          out.push({
            id,
            severity: f.severity,
            applicationId: app.id,
            applicationName: app.name,
            cacheRegion: r.name,
            message: f.message,
            since: iso(this.alertSince.get(id) as number),
          });
        }
      }
    }
    for (const id of [...this.alertSince.keys()]) if (!seen.has(id)) this.alertSince.delete(id);
    const sev = { CRITICAL: 0, WARNING: 1, INFO: 2 } as const;
    return out.sort((x, y) => sev[x.severity] - sev[y.severity] || x.since.localeCompare(y.since));
  }

  private filterEvents(a: AppRecord, regions: RegionState[], q: URLSearchParams): CacheEvent[] {
    const limit = intParam(q, 'limit', 100, 1, 500);
    const actionsRaw = q.get('actions');
    let actions: CacheAction[] | null = null;
    if (actionsRaw) {
      const list = actionsRaw.split(',').map((s) => s.trim()).filter(Boolean);
      const bad = list.filter((x) => !CACHE_ACTIONS.includes(x as CacheAction));
      if (bad.length > 0) throw new HttpError(400, 'Validation failed', [`actions: unknown value(s) ${bad.join(', ')}`]);
      actions = list as CacheAction[];
    }
    const sinceRaw = q.get('since');
    let since = Number.NEGATIVE_INFINITY;
    if (sinceRaw) {
      since = Date.parse(sinceRaw);
      if (!Number.isFinite(since)) throw new HttpError(400, 'Validation failed', ['since: must be an ISO-8601 instant']);
    }
    void a;
    const all: CacheEvent[] = [];
    for (const r of regions) {
      for (const e of r.events) {
        if (actions && !actions.includes(e.action)) continue;
        if (Date.parse(e.timestamp) < since) continue;
        all.push(e);
      }
    }
    all.sort((x, y) => Date.parse(y.timestamp) - Date.parse(x.timestamp) || y.id - x.id);
    return all.slice(0, limit);
  }

  private timelineQuery(q: URLSearchParams): { minutes: number; bucketSeconds: number } {
    return {
      minutes: intParam(q, 'minutes', 15, 1, 120),
      bucketSeconds: intParam(q, 'bucketSeconds', 10, 5, 300),
    };
  }

  private configOf(r: RegionState): RegionConfig {
    const d = r.desired;
    const pending =
      d !== null &&
      ((d.maximumEntries != null && d.maximumEntries !== r.capacity) ||
        (d.maximumMemoryBytes != null && d.maximumMemoryBytes !== r.maxMemoryBytes) ||
        (d.defaultTtlMs != null && d.defaultTtlMs !== r.defaultTtlMs));
    return {
      cacheRegion: r.name,
      riskLevel: r.riskLevel,
      reported: {
        maximumEntries: r.capacity,
        maximumMemoryBytes: r.maxMemoryBytes,
        defaultTtlMs: r.defaultTtlMs,
        activePolicy: r.policy,
      },
      desired: d,
      pending,
      tuningVersion: r.tuningVersion,
      updatedBy: r.updatedBy,
      updatedAt: r.updatedAt,
    };
  }

  private requireText(body: Record<string, unknown>, field: string, min: number, max: number, errors: string[]): string {
    const raw = body[field];
    const text = typeof raw === 'string' ? raw.trim() : '';
    if (text.length === 0) errors.push(`${field}: must not be blank`);
    else if (text.length < min || text.length > max) errors.push(`${field}: size must be between ${min} and ${max}`);
    return text;
  }

  // -------------------------------------------------------------------------
  // Routes
  // -------------------------------------------------------------------------

  private route(method: string, pattern: string, minRole: Role | null, handler: Handler): void {
    const source = pattern.replace(/:[a-zA-Z]+/g, '([^/]+)');
    this.routes.push({ method, pattern: new RegExp(`^${source}$`), minRole, handler });
  }

  private registerRoutes(): void {
    const r = this.route.bind(this);

    // ---- auth --------------------------------------------------------------
    r('POST', '/auth/login', null, (c) => {
      const body = asRecord(c.body);
      const username = typeof body.username === 'string' ? body.username.trim() : '';
      const password = typeof body.password === 'string' ? body.password : '';
      const errors: string[] = [];
      if (!username) errors.push('username: must not be blank');
      if (!password) errors.push('password: must not be blank');
      if (errors.length > 0) throw new HttpError(400, 'Validation failed', errors);
      const user = USERS[username.toLowerCase()];
      if (!user || user.password !== password) {
        this.addAudit({ username, role: 'VIEWER' }, 'LOGIN_FAILURE', 'USER', username, null, 'Login failed', 'FAILURE');
        throw new HttpError(401, 'Invalid username or password');
      }
      const uname = username.toLowerCase();
      this.addAudit({ username: uname, role: user.role }, 'LOGIN_SUCCESS', 'USER', uname, null, 'Login succeeded', 'SUCCESS');
      return ok(this.issueToken(uname, user.role));
    });

    r('POST', '/auth/demo-login', null, (c) => {
      if (!this.demoMode) throw new HttpError(404, 'Not found');
      const role = asRecord(c.body).role;
      if (typeof role !== 'string' || !ROLES.includes(role as Role)) {
        throw new HttpError(400, 'Validation failed', ['role: must be one of VIEWER, ENGINEER, ADMIN']);
      }
      const uname = role.toLowerCase();
      this.addAudit({ username: uname, role: role as Role }, 'LOGIN_SUCCESS', 'USER', uname, null, 'Demo login succeeded', 'SUCCESS');
      return ok(this.issueToken(uname, role as Role));
    });

    r('GET', '/auth/me', 'VIEWER', (c) => ok({ username: c.user?.username, role: c.user?.role }));
    r('GET', '/health', null, () =>
      ok({ status: 'UP', service: 'telemetry-service (mock)', version: '1.0.0', demoMode: this.demoMode }),
    );

    // ---- projects ----------------------------------------------------------
    r('GET', '/projects', 'VIEWER', () => ok(this.projects.map((p) => this.toProject(p))));
    r('POST', '/projects', 'ADMIN', (c) => {
      const body = asRecord(c.body);
      const errors: string[] = [];
      const name = this.requireText(body, 'name', 2, 80, errors);
      if (errors.length > 0) throw new HttpError(400, 'Validation failed', errors);
      if (this.projects.some((p) => p.name.toLowerCase() === name.toLowerCase())) {
        throw new HttpError(409, `A project named '${name}' already exists`);
      }
      this.ids.project += 1;
      const p: ProjectRecord = {
        id: this.ids.project,
        name,
        description: typeof body.description === 'string' ? body.description.trim() : '',
        createdAt: this.clock(),
      };
      this.projects.push(p);
      this.addAudit(c.user as AuthUser, 'PROJECT_CREATED', 'PROJECT', String(p.id), null, `Created project '${name}'`, 'SUCCESS');
      return created(this.toProject(p));
    });
    r('GET', '/projects/:id', 'VIEWER', (c) => ok(this.toProject(this.project(c.params[0] as string))));
    r('GET', '/projects/:id/metrics', 'VIEWER', (c) => {
      const p = this.project(c.params[0] as string);
      const summaries = this.apps.filter((a) => a.projectId === p.id).map((a) => this.summaryOf(a));
      const body: ProjectMetrics = {
        projectId: p.id,
        projectName: p.name,
        applicationCount: summaries.length,
        regionCount: summaries.reduce((n, s) => n + s.regionCount, 0),
        totals: sumTotals(summaries.filter((s) => s.regionCount > 0).map((s) => s.totals)),
        health:
          summaries.filter((s) => s.regionCount > 0).length === 0
            ? { status: 'UNKNOWN', score: 0, reasons: ['No telemetry received yet.'] }
            : (summaries
                .filter((s) => s.regionCount > 0)
                .reduce((w, s) => (healthRank(s.health.status) > healthRank(w.health.status) ? s : w)).health),
        applications: summaries,
      };
      return ok(body);
    });

    // ---- applications ------------------------------------------------------
    r('GET', '/projects/:id/applications', 'VIEWER', (c) => {
      const p = this.project(c.params[0] as string);
      return ok(this.apps.filter((a) => a.projectId === p.id).map((a) => this.toApplication(a)));
    });
    r('POST', '/projects/:id/applications', 'ADMIN', (c) => {
      const p = this.project(c.params[0] as string);
      const body = asRecord(c.body);
      const errors: string[] = [];
      const name = typeof body.name === 'string' ? body.name.trim() : '';
      const environment = typeof body.environment === 'string' ? body.environment.trim() : '';
      if (!/^[a-z0-9][a-z0-9-]{1,62}$/.test(name)) errors.push('name: must match ^[a-z0-9][a-z0-9-]{1,62}$');
      if (!/^[a-z][a-z0-9-]{1,30}$/.test(environment)) errors.push('environment: must match ^[a-z][a-z0-9-]{1,30}$');
      if (errors.length > 0) throw new HttpError(400, 'Validation failed', errors);
      if (this.apps.some((a) => a.name === name && a.environment === environment)) {
        throw new HttpError(409, `Application '${name}' already exists in environment '${environment}'`);
      }
      this.ids.app += 1;
      const app: AppRecord = {
        id: this.ids.app,
        projectId: p.id,
        name,
        displayName:
          typeof body.displayName === 'string' && body.displayName.trim() ? body.displayName.trim() : name,
        environment,
        description: typeof body.description === 'string' ? body.description.trim() : '',
        createdAt: this.clock(),
        regions: new Map(),
      };
      this.apps.push(app);
      this.addAudit(c.user as AuthUser, 'APPLICATION_CREATED', 'APPLICATION', String(app.id), app.id, `Created application '${name}' (${environment})`, 'SUCCESS');
      return created(this.toApplication(app));
    });
    r('GET', '/applications', 'VIEWER', () => ok(this.apps.map((a) => this.toApplication(a))));
    r('GET', '/applications/:id', 'VIEWER', (c) => ok(this.toApplication(this.app(c.params[0] as string))));

    // ---- API keys ----------------------------------------------------------
    const maskKey = (prefix: string) => `${prefix}_${'•'.repeat(16)}`;
    const toApiKey = (k: ApiKeyRecord): ApiKey => ({
      id: k.id,
      applicationId: k.applicationId,
      label: k.label,
      keyPrefix: k.keyPrefix,
      maskedKey: maskKey(k.keyPrefix),
      createdAt: iso(k.createdAt),
      lastUsedAt: k.lastUsedAt === null ? null : iso(k.lastUsedAt),
      revokedAt: k.revokedAt === null ? null : iso(k.revokedAt),
      active: k.revokedAt === null,
    });
    r('GET', '/applications/:id/api-keys', 'ADMIN', (c) => {
      const a = this.app(c.params[0] as string);
      return ok(this.apiKeys.filter((k) => k.applicationId === a.id).map(toApiKey).sort((x, y) => y.id - x.id));
    });
    r('POST', '/applications/:id/api-keys', 'ADMIN', (c) => {
      const a = this.app(c.params[0] as string);
      const errors: string[] = [];
      const label = this.requireText(asRecord(c.body), 'label', 1, 80, errors);
      if (errors.length > 0) throw new HttpError(400, 'Validation failed', errors);
      this.ids.key += 1;
      const prefix = `acc_${this.rng.hex(8)}`;
      const rec: ApiKeyRecord = {
        id: this.ids.key,
        applicationId: a.id,
        label,
        keyPrefix: prefix,
        createdAt: this.clock(),
        lastUsedAt: null,
        revokedAt: null,
      };
      this.apiKeys.push(rec);
      this.addAudit(c.user as AuthUser, 'API_KEY_CREATED', 'API_KEY', String(rec.id), a.id, `Created key '${label}' (${prefix})`, 'SUCCESS');
      const body: ApiKeyCreated = {
        id: rec.id,
        applicationId: a.id,
        label,
        keyPrefix: prefix,
        maskedKey: maskKey(prefix),
        apiKey: `${prefix}_${this.rng.hex(32)}`,
        createdAt: iso(rec.createdAt),
      };
      return created(body);
    });
    r('DELETE', '/api-keys/:id', 'ADMIN', (c) => {
      const key = this.apiKeys.find((k) => String(k.id) === c.params[0]);
      if (!key) throw new HttpError(404, `API key ${c.params[0]} not found`);
      if (key.revokedAt === null) {
        key.revokedAt = this.clock();
        this.addAudit(c.user as AuthUser, 'API_KEY_REVOKED', 'API_KEY', String(key.id), key.applicationId, `Revoked key '${key.label}' (${key.keyPrefix})`, 'SUCCESS');
      }
      return { status: 204 };
    });

    // ---- metrics -----------------------------------------------------------
    r('GET', '/overview', 'VIEWER', () => {
      const reporting = this.apps.filter((a) => a.regions.size > 0);
      const regions = reporting.flatMap((a) => this.metricsOf(a));
      const recs = reporting.flatMap((a) => this.recommendationsOf(a));
      recs.sort((x, y) => (x.action === y.action ? y.createdAt.localeCompare(x.createdAt) : x.action === 'SWITCH' ? -1 : 1));
      const body: Overview = {
        generatedAt: iso(this.clock()),
        projectsMonitored: new Set(reporting.map((a) => a.projectId)).size,
        applicationsMonitored: reporting.length,
        cacheRegionsMonitored: regions.length,
        totals: sumTotals(regions),
        activeAlerts: this.alerts(),
        latestRecommendations: recs.slice(0, 6),
        applications: this.apps.map((a) => this.summaryOf(a)).filter((s) => s.regionCount > 0),
        regions,
      };
      return ok(body);
    });
    r('GET', '/applications/:id/metrics', 'VIEWER', (c) => {
      const a = this.app(c.params[0] as string);
      const body: ApplicationMetrics = { ...this.summaryOf(a), regions: this.metricsOf(a) };
      return ok(body);
    });
    r('GET', '/applications/:id/regions', 'VIEWER', (c) => ok(this.metricsOf(this.app(c.params[0] as string))));
    r('GET', '/applications/:id/regions/:region/metrics', 'VIEWER', (c) => {
      const a = this.app(c.params[0] as string);
      return ok(regionMetrics(this.region(a, c.params[1] as string), a, this.clock()));
    });
    r('GET', '/applications/:id/regions/:region/health', 'VIEWER', (c) => {
      const a = this.app(c.params[0] as string);
      return ok(healthOfRegion(this.region(a, c.params[1] as string), this.clock()).health);
    });
    r('GET', '/applications/:id/regions/:region/events', 'VIEWER', (c) => {
      const a = this.app(c.params[0] as string);
      return ok(this.filterEvents(a, [this.region(a, c.params[1] as string)], c.query));
    });
    r('GET', '/applications/:id/events', 'VIEWER', (c) => {
      const a = this.app(c.params[0] as string);
      return ok(this.filterEvents(a, this.regionList(a), c.query));
    });
    r('GET', '/applications/:id/regions/:region/timeline', 'VIEWER', (c) => {
      const a = this.app(c.params[0] as string);
      const region = this.region(a, c.params[1] as string);
      const { minutes, bucketSeconds } = this.timelineQuery(c.query);
      return ok(buildTimeline([region.history], this.clock(), minutes, bucketSeconds, region.name));
    });
    r('GET', '/applications/:id/timeline', 'VIEWER', (c) => {
      const a = this.app(c.params[0] as string);
      const { minutes, bucketSeconds } = this.timelineQuery(c.query);
      return ok(buildTimeline(this.regionList(a).map((x) => x.history), this.clock(), minutes, bucketSeconds, null));
    });
    r('GET', '/timeline', 'VIEWER', (c) => {
      const { minutes, bucketSeconds } = this.timelineQuery(c.query);
      const histories = this.apps.flatMap((a) => this.regionList(a).map((x) => x.history));
      return ok(buildTimeline(histories, this.clock(), minutes, bucketSeconds, null));
    });

    // ---- region configuration ---------------------------------------------
    r('GET', '/applications/:id/regions/:region/config', 'VIEWER', (c) => {
      const a = this.app(c.params[0] as string);
      return ok(this.configOf(this.region(a, c.params[1] as string)));
    });
    r('PUT', '/applications/:id/regions/:region/config', 'ADMIN', (c) => {
      const a = this.app(c.params[0] as string);
      const region = this.region(a, c.params[1] as string);
      const body = asRecord(c.body) as UpdateRegionConfigRequest & Record<string, unknown>;
      const errors: string[] = [];
      const num = (field: 'maximumEntries' | 'maximumMemoryBytes' | 'defaultTtlMs'): number | undefined => {
        const v = body[field];
        if (v === undefined || v === null) return undefined;
        if (typeof v !== 'number' || !Number.isInteger(v)) {
          errors.push(`${field}: must be a whole number`);
          return undefined;
        }
        return v;
      };
      const maximumEntries = num('maximumEntries');
      const maximumMemoryBytes = num('maximumMemoryBytes');
      const defaultTtlMs = num('defaultTtlMs');
      if (maximumEntries !== undefined && (maximumEntries < CONFIG_BOUNDS.maximumEntries.min || maximumEntries > CONFIG_BOUNDS.maximumEntries.max)) {
        errors.push(`maximumEntries: must be between ${formatInt(CONFIG_BOUNDS.maximumEntries.min)} and ${formatInt(CONFIG_BOUNDS.maximumEntries.max)}`);
      }
      if (maximumMemoryBytes !== undefined && maximumMemoryBytes < CONFIG_BOUNDS.maximumMemoryBytes.min) {
        errors.push(`maximumMemoryBytes: must be at least ${CONFIG_BOUNDS.maximumMemoryBytes.min}`);
      }
      if (defaultTtlMs !== undefined && defaultTtlMs < CONFIG_BOUNDS.defaultTtlMs.min) {
        errors.push(`defaultTtlMs: must be at least ${CONFIG_BOUNDS.defaultTtlMs.min}`);
      }
      if (maximumEntries === undefined && maximumMemoryBytes === undefined && defaultTtlMs === undefined && errors.length === 0) {
        errors.push('at least one of maximumEntries, maximumMemoryBytes, defaultTtlMs is required');
      }
      if (errors.length > 0) throw new HttpError(400, 'Validation failed', errors);
      const reason = typeof body.reason === 'string' ? body.reason.trim() : '';
      region.desired = {
        maximumEntries: maximumEntries ?? region.desired?.maximumEntries ?? null,
        maximumMemoryBytes: maximumMemoryBytes ?? region.desired?.maximumMemoryBytes ?? null,
        defaultTtlMs: defaultTtlMs ?? region.desired?.defaultTtlMs ?? null,
      };
      region.tuningVersion += 1;
      region.updatedBy = (c.user as AuthUser).username;
      region.updatedAt = iso(this.clock());
      const cfg = this.configOf(region);
      region.pendingConfigApplyAtTick = cfg.pending ? region.tickCount + APPLY_DELAY_TICKS : null;
      this.addAudit(
        c.user as AuthUser,
        'CONFIG_CHANGE_REQUESTED',
        'REGION',
        region.name,
        a.id,
        `Requested configuration for '${region.name}' (version ${region.tuningVersion})${reason ? `: ${reason}` : ''}`,
        'SUCCESS',
      );
      return ok(cfg);
    });

    // ---- recommendations & policy change requests --------------------------
    r('GET', '/applications/:id/recommendations', 'VIEWER', (c) => ok(this.recommendationsOf(this.app(c.params[0] as string))));
    r('POST', '/applications/:id/recommendations/evaluate', 'ENGINEER', (c) => {
      const a = this.app(c.params[0] as string);
      this.addAudit(c.user as AuthUser, 'RECOMMENDATION_EVALUATED', 'APPLICATION', String(a.id), a.id, 'Re-evaluated recommendations', 'SUCCESS');
      return ok(this.recommendationsOf(a, true));
    });
    r('GET', '/applications/:id/policy-change-requests', 'VIEWER', (c) => {
      const a = this.app(c.params[0] as string);
      return ok(this.requests.filter((x) => x.applicationId === a.id).sort((x, y) => y.id - x.id));
    });
    r('POST', '/applications/:id/policy-change-requests', 'ENGINEER', (c) => {
      const a = this.app(c.params[0] as string);
      const body = asRecord(c.body);
      const errors: string[] = [];
      const regionName = typeof body.cacheRegion === 'string' ? body.cacheRegion : '';
      if (!regionName) errors.push('cacheRegion: must not be blank');
      const policy = body.requestedPolicy;
      if (typeof policy !== 'string' || !EVICTION_POLICIES.includes(policy as EvictionPolicy)) {
        errors.push('requestedPolicy: must be LRU or LFU');
      }
      if (errors.length > 0) throw new HttpError(400, 'Validation failed', errors);
      const region = this.region(a, regionName);
      if (policy === region.policy) {
        throw new HttpError(400, `Requested policy must differ from the current policy (${region.policy})`);
      }
      if (this.requests.some((x) => x.applicationId === a.id && x.cacheRegion === regionName && x.status === 'PENDING')) {
        throw new HttpError(409, `A policy change request for '${regionName}' is already pending`);
      }
      this.ids.request += 1;
      const req: PolicyChangeRequest = {
        id: this.ids.request,
        applicationId: a.id,
        applicationName: a.name,
        cacheRegion: regionName,
        currentPolicy: region.policy,
        requestedPolicy: policy as EvictionPolicy,
        reason:
          typeof body.reason === 'string' && body.reason.trim() ? body.reason.trim().slice(0, 400) : `Requested ${policy} for '${regionName}'.`,
        status: 'PENDING',
        requestedBy: (c.user as AuthUser).username,
        decidedBy: null,
        decisionNote: null,
        createdAt: iso(this.clock()),
        decidedAt: null,
        appliedAt: null,
        recommendationId: typeof body.recommendationId === 'number' ? body.recommendationId : null,
      };
      this.requests.push(req);
      this.addAudit(c.user as AuthUser, 'POLICY_CHANGE_REQUESTED', 'POLICY_CHANGE_REQUEST', String(req.id), a.id, `Requested ${req.requestedPolicy} for '${regionName}'`, 'SUCCESS');
      return created(req);
    });

    const decide = (c: RouteContext, approve: boolean): MockResponse => {
      const req = this.requests.find((x) => String(x.id) === c.params[0]);
      if (!req) throw new HttpError(404, `Policy change request ${c.params[0]} not found`);
      const user = c.user as AuthUser;
      if (user.role !== 'ADMIN' && req.requestedBy === user.username) {
        this.addAudit(user, 'ACCESS_DENIED', 'POLICY_CHANGE_REQUEST', String(req.id), req.applicationId, `Attempted to ${approve ? 'approve' : 'reject'} own request #${req.id}`, 'DENIED');
        throw new HttpError(403, 'You cannot approve or reject your own request; another engineer or an ADMIN must decide it');
      }
      if (req.status !== 'PENDING') throw new HttpError(409, `Request is already ${req.status}`);
      const note = asRecord(c.body).note;
      req.status = approve ? 'APPROVED' : 'REJECTED';
      req.decidedBy = user.username;
      req.decidedAt = iso(this.clock());
      req.decisionNote = typeof note === 'string' && note.trim() ? note.trim() : null;
      this.addAudit(
        user,
        approve ? 'POLICY_CHANGE_APPROVED' : 'POLICY_CHANGE_REJECTED',
        'POLICY_CHANGE_REQUEST',
        String(req.id),
        req.applicationId,
        `${approve ? 'Approved' : 'Rejected'} request #${req.id} for '${req.cacheRegion}'`,
        'SUCCESS',
      );
      if (approve) {
        const app = this.apps.find((x) => x.id === req.applicationId);
        const region = app?.regions.get(req.cacheRegion);
        if (region) {
          region.pendingPolicy = {
            requestId: req.id,
            policy: req.requestedPolicy,
            applyAtTick: region.tickCount + APPLY_DELAY_TICKS,
          };
        }
      }
      return ok(req);
    };
    r('POST', '/policy-change-requests/:id/approve', 'ENGINEER', (c) => decide(c, true));
    r('POST', '/policy-change-requests/:id/reject', 'ENGINEER', (c) => decide(c, false));

    // ---- audit log ---------------------------------------------------------
    r('GET', '/audit-logs', 'ADMIN', (c) => {
      const limit = intParam(c.query, 'limit', 100, 1, 500);
      const action = c.query.get('action');
      if (action && !AUDIT_ACTIONS.includes(action as AuditAction)) {
        throw new HttpError(400, 'Validation failed', [`action: unknown value ${action}`]);
      }
      const appId = c.query.get('applicationId');
      const rows = this.audit
        .filter((e) => (!action || e.action === action) && (!appId || String(e.applicationId) === appId))
        .sort((x, y) => Date.parse(y.timestamp) - Date.parse(x.timestamp) || y.id - x.id);
      return ok(rows.slice(0, limit));
    });

    // ---- simulations -------------------------------------------------------
    r('POST', '/applications/:id/simulate/:kind', 'ENGINEER', (c) => {
      const a = this.app(c.params[0] as string);
      const kind = c.params[1] as string;
      if (!SIMULATION_KINDS.includes(kind as SimulationKind)) throw new HttpError(404, `Unknown simulation '${kind}'`);
      const requested = asRecord(c.body).requests;
      let requests: number | undefined;
      if (requested !== undefined && requested !== null) {
        const { min, max } = SIMULATION_REQUEST_BOUNDS;
        if (typeof requested !== 'number' || !Number.isInteger(requested) || requested < min || requested > max) {
          throw new HttpError(400, 'Validation failed', [`requests: must be between ${formatInt(min)} and ${formatInt(max)}`]);
        }
        requests = requested;
      }
      this.ids.sim += 1;
      const simId = `sim${this.ids.sim.toString(16).padStart(3, '0')}${this.rng.hex(3)}`;
      const run = runSimulation(kind as SimulationKind, a.id, requests, this.ctxFor(a), this.clock(), simId);
      for (const region of run.regions) a.regions.set(region.name, region);
      this.addAudit(c.user as AuthUser, 'SIMULATION_RUN', 'APPLICATION', String(a.id), a.id, `Ran ${kind} simulation (${run.result.hits + run.result.misses} requests)`, 'SUCCESS');
      return ok(run.result);
    });
  }
}

/** Convenience for tests: the totals of a region exactly as the API would report them. */
export function regionTotals(region: RegionState) {
  return totalsOfRegion(region);
}
