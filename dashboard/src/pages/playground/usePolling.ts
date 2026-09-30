import { useCallback, useEffect, useState } from 'react';

export interface Poll<T> {
  /** The last successful result for the current fetcher; undefined until the first one. */
  data: T | undefined;
  /** The last failure; cleared by the next success. */
  error: Error | null;
  /** Date.now() when data was received (0 before the first result). */
  fetchedAt: number;
  /** Fetch again now (and restart the interval). */
  refresh: () => void;
  /** Update the held data locally, e.g. with a mutation's response, until the next poll. */
  mutate: (update: (data: T) => T) => void;
}

interface State<T> {
  source: (() => Promise<T>) | null;
  data: T | undefined;
  error: Error | null;
  fetchedAt: number;
}

const isVisible = () => document.visibilityState !== 'hidden';

/**
 * Calls {@code fetcher} now and then every {@code intervalMs}, but only while the page is visible
 * (it fetches again as soon as the tab becomes visible). Pass a stable fetcher (useCallback); a new
 * fetcher discards the previous data, and null stops polling. Responses that arrive after the
 * fetcher changed or the component unmounted are ignored.
 */
export function usePolling<T>(fetcher: (() => Promise<T>) | null, intervalMs: number): Poll<T> {
  const [state, setState] = useState<State<T>>({
    source: fetcher,
    data: undefined,
    error: null,
    fetchedAt: 0,
  });
  const [nonce, setNonce] = useState(0);

  useEffect(() => {
    if (!fetcher) return;
    let cancelled = false;
    let inFlight = false;
    const run = async () => {
      if (inFlight || !isVisible()) return;
      inFlight = true;
      try {
        const data = await fetcher();
        if (!cancelled) setState({ source: fetcher, data, error: null, fetchedAt: Date.now() });
      } catch (e) {
        if (cancelled) return;
        const error = e instanceof Error ? e : new Error(String(e));
        setState((s) =>
          s.source === fetcher
            ? { ...s, error }
            : { source: fetcher, data: undefined, error, fetchedAt: 0 },
        );
      } finally {
        inFlight = false;
      }
    };
    void run();
    const timer = window.setInterval(() => void run(), intervalMs);
    const onVisibility = () => {
      if (isVisible()) void run();
    };
    document.addEventListener('visibilitychange', onVisibility);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, [fetcher, intervalMs, nonce]);

  const refresh = useCallback(() => setNonce((n) => n + 1), []);
  const mutate = useCallback(
    (update: (data: T) => T) =>
      setState((s) => (s.data === undefined ? s : { ...s, data: update(s.data) })),
    [],
  );

  const current = state.source === fetcher ? state : null;
  return {
    data: current?.data,
    error: current?.error ?? null,
    fetchedAt: current?.fetchedAt ?? 0,
    refresh,
    mutate,
  };
}
