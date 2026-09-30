import type {
  CacheConfig,
  CacheInfo,
  CreateCacheRequest,
  DeleteResult,
  EntryView,
  GetResult,
  PolicySnapshot,
  PutRequest,
} from './rest';
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

export async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response;
  try {
    response = await fetch(path, {
      ...init,
      headers: {
        Accept: 'application/json',
        ...(init?.body ? { 'Content-Type': 'application/json' } : {}),
        ...init?.headers,
      },
    });
  } catch {
    throw new ApiError('Cannot reach the CacheLab server. Is it running on port 8080?', 0, null);
  }
  if (!response.ok) {
    const problem = await problemOf(response);
    const message = problem?.detail ?? problem?.title ?? `Request failed (${response.status})`;
    throw new ApiError(message, response.status, problem);
  }
  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
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
};
