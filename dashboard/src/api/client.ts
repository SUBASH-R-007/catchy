import type { ProblemDetail } from './types';

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

export const api = {
  /** GET /api/health */
  health: () => request<Health>('/api/health'),
};
