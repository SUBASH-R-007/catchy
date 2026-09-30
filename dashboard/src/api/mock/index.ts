/**
 * Entry point of the in-browser mock API. This module is only ever loaded through a dynamic
 * `import('./mock')` guarded by `VITE_MOCK_API === 'true'`, so it is not part of the normal bundle.
 */
import { MockBackend, type MockOptions } from './backend';

export { MockBackend } from './backend';
export type { MockOptions, MockRequest, MockResponse } from './backend';

let backend: MockBackend | null = null;
let latencyMs: number | null = null;
let options: MockOptions = {};

function defaultLatency(): number {
  const raw = import.meta.env.VITE_MOCK_LATENCY_MS;
  const n = raw === undefined ? NaN : Number(raw);
  return Number.isFinite(n) && n >= 0 ? n : 120;
}

export function getMockBackend(): MockBackend {
  if (!backend) {
    backend = new MockBackend({
      demoMode: import.meta.env.VITE_MOCK_DEMO_MODE !== 'false',
      ...options,
    });
  }
  return backend;
}

/** Replace the singleton backend (tests / demos). Pass no argument to reset to defaults. */
export function configureMock(next: (MockOptions & { latencyMs?: number }) | undefined = undefined): MockBackend {
  const { latencyMs: latency, ...rest } = next ?? {};
  options = rest;
  latencyMs = latency ?? null;
  backend = null;
  return getMockBackend();
}

function wait(ms: number): Promise<void> {
  return ms <= 0 ? Promise.resolve() : new Promise((resolve) => setTimeout(resolve, ms));
}

/** A `fetch`-compatible function that answers from the mock backend. */
export async function mockFetch(input: string, init: RequestInit = {}): Promise<Response> {
  const url = new URL(input, 'http://mock.local');
  const marker = '/api/v1';
  const at = url.pathname.indexOf(marker);
  const path = at >= 0 ? url.pathname.slice(at + marker.length) || '/' : url.pathname;
  const headers = new Headers(init.headers);
  const auth = headers.get('authorization');
  const token = auth && auth.toLowerCase().startsWith('bearer ') ? auth.slice(7).trim() : null;
  let body: unknown;
  if (typeof init.body === 'string' && init.body.length > 0) {
    try {
      body = JSON.parse(init.body) as unknown;
    } catch {
      body = undefined;
    }
  }
  await wait(latencyMs ?? defaultLatency());
  if (init.signal?.aborted) throw new DOMException('Aborted', 'AbortError');
  const res = getMockBackend().handle({
    method: (init.method ?? 'GET').toUpperCase(),
    path,
    query: url.searchParams,
    body,
    token,
  });
  return new Response(res.status === 204 || res.body === undefined ? null : JSON.stringify(res.body), {
    status: res.status,
    headers: { 'Content-Type': 'application/json' },
  });
}
