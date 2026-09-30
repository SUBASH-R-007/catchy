import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, buildQuery, describeError, request, setUnauthorizedHandler } from './client';
import { api } from './endpoints';
import { clearToken, getToken, setToken } from './session';

function jsonResponse(status: number, body: unknown): Response {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  fetchMock = vi.fn();
  vi.stubGlobal('fetch', fetchMock);
  clearToken();
});

afterEach(() => {
  vi.unstubAllGlobals();
  setUnauthorizedHandler(null);
});

function lastCall(): { url: string; init: RequestInit & { headers: Record<string, string> } } {
  const [url, init] = fetchMock.mock.calls[fetchMock.mock.calls.length - 1] as [string, RequestInit & { headers: Record<string, string> }];
  return { url, init };
}

describe('API client: auth header', () => {
  it('attaches the bearer token to every call', async () => {
    setToken('tok-123');
    fetchMock.mockImplementation(() => Promise.resolve(jsonResponse(200, { username: 'engineer', role: 'ENGINEER' })));
    await api.me();
    await api.listProjects();
    expect(fetchMock).toHaveBeenCalledTimes(2);
    for (const call of fetchMock.mock.calls) {
      const init = call[1] as { headers: Record<string, string> };
      expect(init.headers.Authorization).toBe('Bearer tok-123');
      expect(init.headers.Accept).toBe('application/json');
    }
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/v1/auth/me');
    expect(fetchMock.mock.calls[1]?.[0]).toBe('/api/v1/projects');
  });

  it('sends no Authorization header when signed out', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, []));
    await api.listProjects();
    expect(lastCall().init.headers.Authorization).toBeUndefined();
  });

  it('sends JSON bodies with a content type', async () => {
    setToken('t');
    fetchMock.mockResolvedValue(jsonResponse(201, { id: 1 }));
    await api.createProject({ name: 'Claims Platform', description: 'x' });
    const { url, init } = lastCall();
    expect(url).toBe('/api/v1/projects');
    expect(init.method).toBe('POST');
    expect(init.headers['Content-Type']).toBe('application/json');
    expect(JSON.parse(init.body as string)).toEqual({ name: 'Claims Platform', description: 'x' });
  });

  it('encodes region names and joins event actions', async () => {
    setToken('t');
    fetchMock.mockResolvedValue(jsonResponse(200, []));
    await api.regionEvents(2, 'claim rules/x', { limit: 50, actions: ['MISS', 'EVICTED'] });
    expect(lastCall().url).toBe('/api/v1/applications/2/regions/claim%20rules%2Fx/events?limit=50&actions=MISS%2CEVICTED');
  });
});

describe('API client: 401 handling', () => {
  it('clears the session and notifies the handler on 401', async () => {
    setToken('expired');
    const handler = vi.fn();
    setUnauthorizedHandler(handler);
    fetchMock.mockResolvedValue(jsonResponse(401, { status: 401, error: 'Unauthorized', message: 'Token expired', path: '/api/v1/overview', timestamp: 'x' }));
    await expect(api.overview()).rejects.toMatchObject({ status: 401, message: 'Token expired' });
    expect(getToken()).toBeNull();
    expect(handler).toHaveBeenCalledTimes(1);
  });

  it('does not treat a failed login as an expired session', async () => {
    setToken('still-valid');
    const handler = vi.fn();
    setUnauthorizedHandler(handler);
    fetchMock.mockResolvedValue(jsonResponse(401, { status: 401, error: 'Unauthorized', message: 'Invalid username or password', path: '/api/v1/auth/login', timestamp: 'x' }));
    await expect(api.login({ username: 'a', password: 'b' })).rejects.toBeInstanceOf(ApiError);
    expect(handler).not.toHaveBeenCalled();
    expect(getToken()).toBe('still-valid');
  });
});

describe('API client: errors and edge cases', () => {
  it('exposes validation details from the error body', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(400, { status: 400, error: 'Bad Request', message: 'Validation failed', path: '/api/v1/projects', timestamp: 'x', details: ['name: must not be blank'] }),
    );
    const err = await request('POST', '/projects', { body: {} }).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).details).toEqual(['name: must not be blank']);
    expect(describeError(err)).toBe('Validation failed (name: must not be blank)');
  });

  it('maps network failures to a friendly status-0 error', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'));
    const err = await api.overview().catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).status).toBe(0);
    expect(describeError(err)).toMatch(/cannot reach the telemetry service/i);
  });

  it('falls back to a generic message when the error body is not JSON', async () => {
    fetchMock.mockResolvedValue(new Response('<html>Bad gateway</html>', { status: 502 }));
    await expect(api.overview()).rejects.toMatchObject({ status: 502, message: 'Request failed (502)' });
  });

  it('returns undefined for 204 No Content', async () => {
    setToken('t');
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
    await expect(api.revokeApiKey(4)).resolves.toBeUndefined();
    expect(lastCall().url).toBe('/api/v1/api-keys/4');
    expect(lastCall().init.method).toBe('DELETE');
  });

  it('drops empty query values', () => {
    expect(buildQuery({ limit: 100, action: '', applicationId: null, since: undefined, minutes: 15 })).toBe('?limit=100&minutes=15');
    expect(buildQuery()).toBe('');
  });
});

describe('session storage resilience', () => {
  it('keeps working (in memory) when sessionStorage throws', () => {
    const get = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    const set = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    const remove = vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    expect(() => setToken('mem-token')).not.toThrow();
    expect(getToken()).toBe('mem-token');
    expect(() => clearToken()).not.toThrow();
    expect(getToken()).toBeNull();
    get.mockRestore();
    set.mockRestore();
    remove.mockRestore();
  });
});
