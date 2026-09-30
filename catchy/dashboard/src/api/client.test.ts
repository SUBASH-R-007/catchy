import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from 'vitest';
import { api, ApiError, stampedeTimeoutMs, stressTimeoutMs } from './client';

function json(body: unknown, status = 200, contentType = 'application/json'): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': contentType } });
}

function noContent(): Response {
  return new Response(null, { status: 204 });
}

describe('api (REST wrappers)', () => {
  let fetchMock: Mock<typeof fetch>;

  beforeEach(() => {
    fetchMock = vi.fn<typeof fetch>();
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  const call = (i = 0) => {
    const args = fetchMock.mock.calls[i];
    if (!args) throw new Error(`fetch was not called ${i + 1} time(s)`);
    const [url, init] = args;
    const headers = (init?.headers ?? {}) as Record<string, string>;
    return {
      url: String(url),
      method: init?.method ?? 'GET',
      body: typeof init?.body === 'string' ? (JSON.parse(init.body) as unknown) : undefined,
      headers,
    };
  };

  it('lists caches with a GET', async () => {
    fetchMock.mockResolvedValue(json([{ name: 'a' }]));
    await expect(api.listCaches()).resolves.toEqual([{ name: 'a' }]);
    expect(call()).toMatchObject({ url: '/api/caches', method: 'GET', body: undefined });
  });

  it('creates a cache with a JSON body', async () => {
    const config = { name: 'play-1', policy: 'LFU', capacity: 5, group: 'playground' } as const;
    fetchMock.mockResolvedValue(json({ ...config, defaultTtlMs: null, concurrencyLevel: 1 }, 201));
    const created = await api.createCache(config);
    expect(created.concurrencyLevel).toBe(1);
    const c = call();
    expect(c).toMatchObject({ url: '/api/caches', method: 'POST', body: config });
    expect(c.headers['Content-Type']).toBe('application/json');
  });

  it('deletes a cache and resolves to undefined on 204', async () => {
    fetchMock.mockResolvedValue(noContent());
    await expect(api.deleteCache('play-1')).resolves.toBeUndefined();
    expect(call()).toMatchObject({ url: '/api/caches/play-1', method: 'DELETE' });
  });

  it('URL-encodes cache names and keys', async () => {
    fetchMock.mockResolvedValue(json({ hit: false, value: null, ttlRemainingMs: null }));
    await api.getEntry('a b', 'drug:44 11?x=1&y#z');
    expect(call().url).toBe('/api/caches/a%20b/entries/drug%3A44%2011%3Fx%3D1%26y%23z');
  });

  it('lists entries and snapshots with a limit', async () => {
    fetchMock.mockImplementation(() => Promise.resolve(json([])));
    await api.listEntries('c1', 50);
    await api.snapshot('c1', 20);
    expect(call(0)).toMatchObject({ url: '/api/caches/c1/entries?limit=50', method: 'GET' });
    expect(call(1)).toMatchObject({ url: '/api/caches/c1/snapshot?limit=20', method: 'GET' });
  });

  it('puts an entry with value and TTL', async () => {
    fetchMock.mockResolvedValue(noContent());
    await expect(api.putEntry('c1', 'k1', { value: 'v', ttlMs: 5000 })).resolves.toBeUndefined();
    expect(call()).toMatchObject({
      url: '/api/caches/c1/entries/k1',
      method: 'PUT',
      body: { value: 'v', ttlMs: 5000 },
    });
  });

  it('deletes an entry and returns whether it was removed', async () => {
    fetchMock.mockResolvedValue(json({ removed: true }));
    await expect(api.deleteEntry('c1', 'k1')).resolves.toEqual({ removed: true });
    expect(call()).toMatchObject({ url: '/api/caches/c1/entries/k1', method: 'DELETE' });
  });

  it('switches the policy and resets stats with POSTs', async () => {
    fetchMock
      .mockResolvedValueOnce(json({ name: 'c1', policy: 'LFU_DECAY' }))
      .mockResolvedValueOnce(noContent());
    await api.switchPolicy('c1', 'LFU_DECAY');
    await api.resetStats('c1');
    expect(call(0)).toMatchObject({
      url: '/api/caches/c1/policy',
      method: 'POST',
      body: { policy: 'LFU_DECAY' },
    });
    expect(call(1)).toMatchObject({
      url: '/api/caches/c1/reset-stats',
      method: 'POST',
      body: undefined,
    });
  });

  it('turns a ProblemDetail into an ApiError carrying the detail message', async () => {
    fetchMock.mockResolvedValue(
      json(
        { title: 'Conflict', status: 409, detail: "A cache named 'play-1' already exists" },
        409,
        'application/problem+json',
      ),
    );
    const error = await api.createCache({ name: 'play-1', capacity: 5 }).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).message).toBe("A cache named 'play-1' already exists");
    expect((error as ApiError).status).toBe(409);
  });

  it('explains an unreachable server', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'));
    await expect(api.listCaches()).rejects.toThrow('Cannot reach the CacheLab server');
  });
  it('starts a simulation with the full request body', async () => {
    fetchMock.mockResolvedValue(json({ id: 's-12' }));
    const body = {
      group: 'demo',
      pattern: 'SCAN_POLLUTION',
      opsPerSec: 5000,
      readRatio: 0.9,
      durationSec: 300,
      seed: 42,
    } as const;
    await expect(api.startSimulation(body)).resolves.toEqual({ id: 's-12' });
    expect(call()).toMatchObject({ url: '/api/simulations', method: 'POST', body });
  });

  it('stops a simulation by id', async () => {
    fetchMock.mockResolvedValue(noContent());
    await expect(api.stopSimulation('s 12')).resolves.toBeUndefined();
    expect(call()).toMatchObject({ url: '/api/simulations/s%2012', method: 'DELETE' });
  });

  it('runs a stress test and returns the report', async () => {
    const report = {
      impl: 'SEGMENTED',
      threads: 32,
      durationMs: 5003,
      totalOps: 1_000_000,
      opsPerSec: 199_880,
      invariants: [{ name: 'Size bound', passed: true, detail: 'size 1000 <= 1000' }],
      exceptions: [],
      deadlockFree: true,
    };
    fetchMock.mockResolvedValue(json(report));
    const config = { impl: 'SEGMENTED', threads: 32, durationMs: 5000 } as const;
    await expect(api.runStress(config)).resolves.toEqual(report);
    expect(call()).toMatchObject({ url: '/api/stress', method: 'POST', body: config });
    expect(fetchMock.mock.calls[0]?.[1]?.signal).toBeInstanceOf(AbortSignal);
  });

  it('runs the stampede test', async () => {
    const result = { threads: 200, loaderCalls: 1, allSameValue: true, durationMs: 214 };
    fetchMock.mockResolvedValue(json(result));
    await expect(api.runStampede({ threads: 200, loaderDelayMs: 200 })).resolves.toEqual(result);
    expect(call()).toMatchObject({
      url: '/api/stress/stampede',
      method: 'POST',
      body: { threads: 200, loaderDelayMs: 200 },
    });
  });

  it('surfaces a busy stress harness as a 409 ApiError', async () => {
    fetchMock.mockResolvedValue(
      json({ title: 'Conflict', status: 409, detail: 'A stress job is already running' }, 409),
    );
    const error = await api.runStress({ impl: 'SINGLE_LOCK' }).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(409);
    expect((error as ApiError).message).toBe('A stress job is already running');
  });

  it('never times out a stress run before its duration plus 10 s', () => {
    expect(stressTimeoutMs({ durationMs: 10_000 })).toBe(20_000);
    expect(stressTimeoutMs({})).toBe(15_000);
    expect(stampedeTimeoutMs({ loaderDelayMs: 200 })).toBe(10_200);
  });

  it('aborts a blocking call that outlives its timeout', async () => {
    vi.useFakeTimers();
    try {
      fetchMock.mockImplementation(
        (_url, init) =>
          new Promise((_resolve, reject) => {
            init?.signal?.addEventListener('abort', () =>
              reject(new DOMException('aborted', 'AbortError')),
            );
          }),
      );
      const pending = api.runStress({ durationMs: 1000 }).catch((e: unknown) => e);
      await vi.advanceTimersByTimeAsync(10_999);
      await vi.advanceTimersByTimeAsync(2);
      const error = await pending;
      expect(error).toBeInstanceOf(ApiError);
      expect((error as ApiError).message).toBe('The server did not answer within 11 s.');
    } finally {
      vi.useRealTimers();
    }
  });
});
