import type { ApiErrorBody } from './types';
import { clearToken, getToken } from './session';

/** Error thrown for every non-2xx response and for network failures (`status` 0). */
export class ApiError extends Error {
  readonly status: number;
  readonly body: ApiErrorBody | null;
  readonly details: string[];

  constructor(status: number, message: string, body: ApiErrorBody | null = null) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.body = body;
    this.details = body?.details ?? [];
  }
}

export function isApiError(e: unknown): e is ApiError {
  return e instanceof ApiError;
}

/** A single readable line for toasts / inline errors, including validation details. */
export function describeError(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.status === 0) return e.message;
    const detail = e.details.length > 0 ? ` (${e.details.join('; ')})` : '';
    return `${e.message}${detail}`;
  }
  if (e instanceof Error) return e.message;
  return 'Something went wrong';
}

type UnauthorizedHandler = () => void;
let unauthorizedHandler: UnauthorizedHandler | null = null;

/** Registered by the AuthProvider: called after a 401 has cleared the stored session. */
export function setUnauthorizedHandler(handler: UnauthorizedHandler | null): void {
  unauthorizedHandler = handler;
}

export type QueryValue = string | number | boolean | null | undefined;

export interface RequestOptions {
  body?: unknown;
  query?: Record<string, QueryValue>;
  signal?: AbortSignal;
  /** Login endpoints return 401 for bad credentials — that must not bounce the user to /login. */
  skipAuthRedirect?: boolean;
}

function apiBase(): string {
  const base = import.meta.env.VITE_API_BASE as string | undefined;
  return base ? base.replace(/\/+$/, '') : '';
}

export function buildQuery(query?: Record<string, QueryValue>): string {
  if (!query) return '';
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue;
    params.set(key, String(value));
  }
  const s = params.toString();
  return s ? `?${s}` : '';
}

async function transport(url: string, init: RequestInit): Promise<Response> {
  // The comparison must stay inline: a production build replaces VITE_MOCK_API with a literal, the
  // branch is dead code, and the mock chunk is not even emitted. (Resolved at call time in tests.)
  if (import.meta.env.VITE_MOCK_API === 'true') {
    const mock = await import('./mock');
    return mock.mockFetch(url, init);
  }
  return fetch(url, init);
}

async function parseJson(res: Response): Promise<unknown> {
  const text = await res.text();
  if (!text) return undefined;
  try {
    return JSON.parse(text) as unknown;
  } catch {
    return undefined;
  }
}

function toErrorBody(value: unknown): ApiErrorBody | null {
  if (value && typeof value === 'object' && 'message' in value) return value as ApiErrorBody;
  return null;
}

/** Low-level JSON request against `/api/v1`. Attaches the bearer token to every call. */
export async function request<T>(method: string, path: string, options: RequestOptions = {}): Promise<T> {
  const url = `${apiBase()}/api/v1${path}${buildQuery(options.query)}`;
  const headers: Record<string, string> = { Accept: 'application/json' };
  const token = getToken();
  if (token) headers.Authorization = `Bearer ${token}`;
  const init: RequestInit = { method, headers, signal: options.signal };
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(options.body);
  }

  let res: Response;
  try {
    res = await transport(url, init);
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') throw e;
    throw new ApiError(0, 'Cannot reach the telemetry service. Check that it is running and try again.');
  }

  if (res.status === 401 && !options.skipAuthRedirect) {
    clearToken();
    unauthorizedHandler?.();
  }

  const payload = await parseJson(res);
  if (!res.ok) {
    const body = toErrorBody(payload);
    throw new ApiError(res.status, body?.message ?? `Request failed (${res.status})`, body);
  }
  return payload as T;
}

export const http = {
  get: <T>(path: string, query?: Record<string, QueryValue>, signal?: AbortSignal) =>
    request<T>('GET', path, { query, signal }),
  post: <T>(path: string, body?: unknown, options: Omit<RequestOptions, 'body'> = {}) =>
    request<T>('POST', path, { ...options, body: body ?? undefined }),
  put: <T>(path: string, body?: unknown) => request<T>('PUT', path, { body }),
  delete: <T>(path: string) => request<T>('DELETE', path),
};
