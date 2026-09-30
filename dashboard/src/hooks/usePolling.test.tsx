import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useFetch, usePolling } from './usePolling';

function setHidden(hidden: boolean) {
  Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden });
  document.dispatchEvent(new Event('visibilitychange'));
}

function deferred<T>() {
  let resolve!: (v: T) => void;
  let reject!: (e: Error) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

beforeEach(() => {
  vi.useFakeTimers();
  setHidden(false);
});
afterEach(() => {
  vi.useRealTimers();
  setHidden(false);
});

async function advance(ms: number) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
}

describe('usePolling', () => {
  it('fetches immediately, then every 3 seconds after the previous request finished', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce('one').mockResolvedValueOnce('two').mockResolvedValue('three');
    const { result } = renderHook(() => usePolling(fetcher, []));
    expect(result.current.loading).toBe(true);
    await advance(0);
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(result.current.data).toBe('one');
    expect(result.current.loading).toBe(false);
    expect(result.current.updatedAt).not.toBeNull();
    await advance(2999);
    expect(fetcher).toHaveBeenCalledTimes(1);
    await advance(1);
    expect(fetcher).toHaveBeenCalledTimes(2);
    expect(result.current.data).toBe('two');
  });

  it('never has two requests in flight at once', async () => {
    const pending: Array<ReturnType<typeof deferred<string>>> = [];
    const fetcher = vi.fn(() => {
      const d = deferred<string>();
      pending.push(d);
      return d.promise;
    });
    renderHook(() => usePolling(fetcher, []));
    await advance(0);
    expect(fetcher).toHaveBeenCalledTimes(1);
    // a slow request: several intervals pass without a second call
    await advance(10_000);
    expect(fetcher).toHaveBeenCalledTimes(1);
    await act(async () => pending[0]?.resolve('done'));
    await advance(2999);
    expect(fetcher).toHaveBeenCalledTimes(1);
    await advance(1);
    expect(fetcher).toHaveBeenCalledTimes(2);
  });

  it('pauses while the tab is hidden and refreshes immediately when it is shown again', async () => {
    const fetcher = vi.fn().mockResolvedValue('x');
    const { result } = renderHook(() => usePolling(fetcher, []));
    await advance(0);
    expect(fetcher).toHaveBeenCalledTimes(1);
    act(() => setHidden(true));
    expect(result.current.paused).toBe(true);
    await advance(20_000);
    expect(fetcher).toHaveBeenCalledTimes(1);
    act(() => setHidden(false));
    await advance(0);
    expect(fetcher).toHaveBeenCalledTimes(2);
    expect(result.current.paused).toBe(false);
    await advance(3000);
    expect(fetcher).toHaveBeenCalledTimes(3);
  });

  it('keeps the last good data when a later poll fails, and recovers', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce('good').mockRejectedValueOnce(new Error('boom')).mockResolvedValue('better');
    const { result } = renderHook(() => usePolling(fetcher, []));
    await advance(0);
    expect(result.current.data).toBe('good');
    await advance(3000);
    expect(result.current.data).toBe('good');
    expect(result.current.error?.message).toBe('boom');
    await advance(3000);
    expect(result.current.data).toBe('better');
    expect(result.current.error).toBeNull();
  });

  it('reports the error when the very first request fails', async () => {
    const fetcher = vi.fn().mockRejectedValue(new Error('down'));
    const { result } = renderHook(() => usePolling(fetcher, []));
    await advance(0);
    expect(result.current.data).toBeUndefined();
    expect(result.current.error?.message).toBe('down');
    expect(result.current.loading).toBe(false);
  });

  it('restarts when deps change and ignores the stale response', async () => {
    const first = deferred<string>();
    const fetcher = vi.fn((id: number) => (id === 1 ? first.promise : Promise.resolve(`app-${id}`)));
    const { result, rerender } = renderHook(({ id }) => usePolling(() => fetcher(id), [id]), { initialProps: { id: 1 } });
    await advance(0);
    rerender({ id: 2 });
    await advance(0);
    expect(result.current.data).toBe('app-2');
    await act(async () => first.resolve('app-1'));
    expect(result.current.data).toBe('app-2');
  });

  it('refresh() fetches right away and waits for an in-flight request instead of overlapping', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce('a').mockResolvedValue('b');
    const { result } = renderHook(() => usePolling(fetcher, []));
    await advance(0);
    await act(async () => {
      await result.current.refresh();
    });
    expect(fetcher).toHaveBeenCalledTimes(2);
    expect(result.current.data).toBe('b');
  });

  it('does nothing when disabled', async () => {
    const fetcher = vi.fn().mockResolvedValue('x');
    const { result } = renderHook(() => usePolling(fetcher, [], { enabled: false }));
    await advance(10_000);
    expect(fetcher).not.toHaveBeenCalled();
    expect(result.current.loading).toBe(false);
  });

  it('stops polling after unmount', async () => {
    const fetcher = vi.fn().mockResolvedValue('x');
    const { unmount } = renderHook(() => usePolling(fetcher, []));
    await advance(0);
    unmount();
    await advance(20_000);
    expect(fetcher).toHaveBeenCalledTimes(1);
  });
});

describe('useFetch', () => {
  it('fetches once and only again on demand', async () => {
    const fetcher = vi.fn().mockResolvedValue('once');
    const { result } = renderHook(() => useFetch(fetcher, []));
    await advance(0);
    await advance(30_000);
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(result.current.paused).toBe(false);
    await act(async () => {
      await result.current.refresh();
    });
    expect(fetcher).toHaveBeenCalledTimes(2);
  });
});
