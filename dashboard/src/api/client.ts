import type {
  CacheConfig,
  CacheInfo,
  CreateCacheRequest,
  DeleteResult,
  EntryView,
  GetResult,
  PolicySnapshot,
  PutRequest,
  SimulationRequest,
  SimulationStarted,
  StampedeRequest,
  StampedeResult,
  StressConfig,
  StressReport,
} from './rest';
import type { BenchResults, DemoAct } from './rest';
import type { PolicyType, ProblemDetail } from './types';

/**
 * Typed fetch wrappers for the REST API (SPEC 8.2). Endpoints are added here as the steps that
 * deliver them land; errors surface as ApiError carrying the server's RFC 7807 message, which
 * callers show as a toast.
 */

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly problem: ProblemDetail | null,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

async function problemOf(response: Response): Promise<ProblemDetail | null> {
  try {
    const body: unknown = await response.json();
    return typeof body === 'object' && body !== null ? (body as ProblemDetail) : null;
  } catch {
    return null;
  }
}

/**
 * Fetches a JSON endpoint. With {@code timeoutMs}, the request is aborted (and rejects with an
 * ApiError) when the server has not answered in time; without it, the request waits indefinitely.
 */
export async function request<T>(path: string, init?: RequestInit, timeoutMs?: number): Promise<T> {
  const controller = timeoutMs === undefined ? null : new AbortController();
  const timer = controller === null ? undefined : setTimeout(() => controller.abort(), timeoutMs);
  let response: Response;
  try {
    response = await fetch(path, {
      ...init,
      ...(controller ? { signal: controller.signal } : {}),
      headers: {
        Accept: 'application/json',
        ...(init?.body ? { 'Content-Type': 'application/json' } : {}),
        ...init?.headers,
      },
    });
  } catch {
    if (controller?.signal.aborted) {
      throw new ApiError(
        `The server did not answer within ${Math.round((timeoutMs ?? 0) / 1000)} s.`,
        0,
        null,
      );
    }
    throw new ApiError('Cannot reach the CacheLab server. Is it running on port 8080?', 0, null);
  } finally {
    clearTimeout(timer);
  }
  if (!response.ok) {
    const problem = await problemOf(response);
    const message = problem?.detail ?? problem?.title ?? `Request failed (${response.status})`;
    throw new ApiError(message, response.status, problem);
  }
  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}

/** Server default stress duration (openapi StressConfig.durationMs). */
export const DEFAULT_STRESS_DURATION_MS = 5000;

/** Extra time a blocking diagnostics call may take beyond its own duration before we give up. */
export const DIAGNOSTICS_GRACE_MS = 10_000;

/** Client timeout for POST /api/stress: the run's duration plus a 10 s grace period. */
export function stressTimeoutMs(config: StressConfig): number {
  return (config.durationMs ?? DEFAULT_STRESS_DURATION_MS) + DIAGNOSTICS_GRACE_MS;
}

/** Client timeout for POST /api/stress/stampede: the loader delay plus a 10 s grace period. */
export function stampedeTimeoutMs(body: StampedeRequest): number {
  return (body.loaderDelayMs ?? 200) + DIAGNOSTICS_GRACE_MS;
}

export interface Health {
  status: string;
}

/** "/api/caches/{name}" with the name URL-encoded. */
function cachePath(name: string): string {
  return `/api/caches/${encodeURIComponent(name)}`;
}

function entryPath(name: string, key: string): string {
  return `${cachePath(name)}/entries/${encodeURIComponent(key)}`;
}

function post(body?: unknown): RequestInit {
  return body === undefined ? { method: 'POST' } : { method: 'POST', body: JSON.stringify(body) };
}

export const api = {
  /** GET /api/health */
  health: () => request<Health>('/api/health'),

  /** GET /api/caches: every cache with its live stats, sorted by group then name. */
  listCaches: () => request<CacheInfo[]>('/api/caches'),

  /** POST /api/caches → 201 CacheConfig. */
  createCache: (config: CreateCacheRequest) => request<CacheConfig>('/api/caches', post(config)),

  /** DELETE /api/caches/{name} → 204. */
  deleteCache: (name: string) => request<void>(cachePath(name), { method: 'DELETE' }),

  /** GET /api/caches/{name}/entries?limit= (read-only; does not count as access). */
  listEntries: (name: string, limit = 50) =>
    request<EntryView[]>(`${cachePath(name)}/entries?limit=${limit}`),

  /** GET /api/caches/{name}/entries/{key} (records a normal get). */
  getEntry: (name: string, key: string) => request<GetResult>(entryPath(name, key)),

  /** PUT /api/caches/{name}/entries/{key} → 204. */
  putEntry: (name: string, key: string, body: PutRequest) =>
    request<void>(entryPath(name, key), { method: 'PUT', body: JSON.stringify(body) }),

  /** DELETE /api/caches/{name}/entries/{key} → {removed}. */
  deleteEntry: (name: string, key: string) =>
    request<DeleteResult>(entryPath(name, key), { method: 'DELETE' }),

  /** GET /api/caches/{name}/snapshot?limit= */
  snapshot: (name: string, limit = 20) =>
    request<PolicySnapshot>(`${cachePath(name)}/snapshot?limit=${limit}`),

  /** POST /api/caches/{name}/policy → the updated CacheConfig. */
  switchPolicy: (name: string, policy: PolicyType) =>
    request<CacheConfig>(`${cachePath(name)}/policy`, post({ policy })),

  /** POST /api/caches/{name}/reset-stats → 204. */
  resetStats: (name: string) => request<void>(`${cachePath(name)}/reset-stats`, post()),

  /** POST /api/simulations → {id}. Replaces any running simulation. */
  startSimulation: (body: SimulationRequest) =>
    request<SimulationStarted>('/api/simulations', post(body)),

  /** DELETE /api/simulations/{id} → 204 (no-op if it already finished). */
  stopSimulation: (id: string) =>
    request<void>(`/api/simulations/${encodeURIComponent(id)}`, { method: 'DELETE' }),

  /** POST /api/stress: blocks for the run's duration; 409 while another stress job runs. */
  runStress: (config: StressConfig) =>
    request<StressReport>('/api/stress', post(config), stressTimeoutMs(config)),

  /** POST /api/stress/stampede: many threads load one missing key; 409 while busy. */
  runStampede: (body: StampedeRequest) =>
    request<StampedeResult>('/api/stress/stampede', post(body), stampedeTimeoutMs(body)),
};

// ---------------------------------------------------------------------------------------------
// Step 4 · advisor, traces and reports (SPEC 6.3, 8.2, 9.5): used by the Policy Race and Trace
// Replay pages. Kept in its own block (types imported inline) so other Step 4 wrappers can be
// appended independently.
// ---------------------------------------------------------------------------------------------

type AdvisorApplyResult = import('./rest').AdvisorApplyResult;
type TraceUploaded = import('./rest').TraceUploaded;
type ReplayRequest = import('./rest').ReplayRequest;
type ReplayResult = import('./rest').ReplayResult;

/** The last simulation's per-cache summary (404 until a simulation has run). */
export const LATEST_REPORT_CSV_URL = '/api/reports/latest.csv';
export const LATEST_REPORT_JSON_URL = '/api/reports/latest.json';

/** Client timeout for a trace replay: the server must finish 100k rows in under 10 s (SPEC 9.5). */
export const REPLAY_TIMEOUT_MS = 60_000;

/** Client timeout for a trace upload (up to 20 MB). */
export const UPLOAD_TIMEOUT_MS = 120_000;

/**
 * POSTs a multipart form. Unlike {@link request}, it lets the browser set the multipart
 * Content-Type (with its boundary); errors are ApiErrors carrying the ProblemDetail message.
 */
async function postForm<T>(path: string, form: FormData, timeoutMs: number): Promise<T> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  let response: Response;
  try {
    response = await fetch(path, {
      method: 'POST',
      body: form,
      headers: { Accept: 'application/json' },
      signal: controller.signal,
    });
  } catch {
    if (controller.signal.aborted) {
      throw new ApiError(`The server did not answer within ${Math.round(timeoutMs / 1000)} s.`, 0, null);
    }
    throw new ApiError('Cannot reach the CacheLab server. Is it running on port 8080?', 0, null);
  } finally {
    clearTimeout(timer);
  }
  if (!response.ok) {
    const problem = await problemOf(response);
    const message = problem?.detail ?? problem?.title ?? `Request failed (${response.status})`;
    throw new ApiError(message, response.status, problem);
  }
  return (await response.json()) as T;
}

export const advisorApi = {
  /** POST /api/groups/{group}/advisor/apply → {switchedTo}; 409 when there is no recommendation. */
  apply: (group: string) =>
    request<AdvisorApplyResult>(`/api/groups/${encodeURIComponent(group)}/advisor/apply`, post()),
};

export const traceApi = {
  /** POST /api/traces (multipart field "file") → {traceId, rows}; 400/413 on bad files. */
  upload: (file: File) => {
    const form = new FormData();
    form.append('file', file);
    return postForm<TraceUploaded>('/api/traces', form, UPLOAD_TIMEOUT_MS);
  },

  /** POST /api/traces/sample: loads samples/formulary-trace.csv → {traceId, rows}. */
  sample: () => request<TraceUploaded>('/api/traces/sample', post(), UPLOAD_TIMEOUT_MS),

  /** POST /api/traces/{id}/replay: every chosen policy plus the optimal, offline. */
  replay: (traceId: string, body: ReplayRequest) =>
    request<ReplayResult>(
      `/api/traces/${encodeURIComponent(traceId)}/replay`,
      post(body),
      REPLAY_TIMEOUT_MS,
    ),
};

// ---- Benchmarks and guided demo (SPEC 5, 9.4) -----------------------------------------------

/** Typed wrappers for /api/bench and /api/demo; kept apart from `api` to keep appends additive. */
export const benchDemoApi = {
  /** GET /api/bench: JMH results (or the sample file, flagged `sample: true`). */
  bench: () => request<BenchResults>('/api/bench'),

  /** POST /api/demo/acts/{n}/start (n = 1-4): resets the act's group and starts its phases. */
  startDemoAct: (act: number) => request<DemoAct>(`/api/demo/acts/${act}/start`, post()),

  /** POST /api/demo/stop → 204. Stops the running act's workload. */
  stopDemo: () => request<void>('/api/demo/stop', post()),
};
