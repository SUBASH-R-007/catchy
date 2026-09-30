import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from 'vitest';
import { api, ApiError } from './client';

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
});
