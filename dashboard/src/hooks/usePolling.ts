import { useEffect, useRef, useState, type DependencyList } from 'react';

export const POLL_INTERVAL_MS = 3000;

export interface PollingOptions {
  /** Delay between the end of one request and the start of the next. `null` = fetch once (no polling). */
  intervalMs?: number | null;
  /** When false nothing is fetched (e.g. waiting for a selection). */
  enabled?: boolean;
  /** Keep showing the previous data while a new `deps` value loads (filters). Default: clear it. */
  keepPrevious?: boolean;
}

export interface PollingResult<T> {
  data: T | undefined;
  error: Error | null;
  /** True until the first response (or error) for the current `deps` arrives. */
  loading: boolean;
  /** True while a request is in flight and data from an earlier response is still on screen. */
  refreshing: boolean;
  /** Epoch ms of the last successful response. */
  updatedAt: number | null;
  /** True while the tab is hidden and polling is suspended. */
  paused: boolean;
  /** Fetch again now (used after mutations). Never overlaps an in-flight request. */
  refresh: () => Promise<void>;
}

interface State<T> {
  data: T | undefined;
  error: Error | null;
  updatedAt: number | null;
  settled: boolean;
  busy: boolean;
}

function toError(e: unknown): Error {
  return e instanceof Error ? e : new Error(String(e));
}

function isHidden(): boolean {
  return typeof document !== 'undefined' && document.hidden;
}

export function usePageVisible(): boolean {
  const [visible, setVisible] = useState(() => !isHidden());
  useEffect(() => {
    const onChange = () => setVisible(!isHidden());
    document.addEventListener('visibilitychange', onChange);
    return () => document.removeEventListener('visibilitychange', onChange);
  }, []);
  return visible;
}

/**
 * Tiny polling hook: one request at a time (the next one is scheduled only after the previous
 * one settles), suspended while the tab is hidden, and resumed immediately when it is shown again.
 * Re-runs from scratch whenever `deps` change.
 */
export function usePolling<T>(
  fetcher: () => Promise<T>,
  deps: DependencyList,
  options: PollingOptions = {},
): PollingResult<T> {
  const { intervalMs = POLL_INTERVAL_MS, enabled = true, keepPrevious = false } = options;
  const fetcherRef = useRef(fetcher);
  fetcherRef.current = fetcher;
  const refreshRef = useRef<() => Promise<void>>(() => Promise.resolve());
  const visible = usePageVisible();
  const [state, setState] = useState<State<T>>({
    data: undefined,
    error: null,
    updatedAt: null,
    settled: false,
    busy: enabled,
  });

  useEffect(() => {
    if (!enabled) {
      setState((s) => ({ ...s, busy: false }));
      refreshRef.current = () => Promise.resolve();
      return undefined;
    }
    let cancelled = false;
    let inFlight = false;
    let again = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let waiters: Array<() => void> = [];

    setState((s) =>
      keepPrevious ? { ...s, busy: true } : { data: undefined, error: null, updatedAt: null, settled: false, busy: true },
    );

    const settleWaiters = () => {
      const list = waiters;
      waiters = [];
      list.forEach((resolve) => resolve());
    };

    const execute = async (): Promise<void> => {
      if (cancelled) return;
      if (inFlight) {
        again = true;
        return new Promise<void>((resolve) => waiters.push(resolve));
      }
      inFlight = true;
      setState((s) => (s.busy ? s : { ...s, busy: true }));
      try {
        const data = await fetcherRef.current();
        if (!cancelled) setState({ data, error: null, updatedAt: Date.now(), settled: true, busy: false });
      } catch (e) {
        if (!cancelled) setState((s) => ({ ...s, error: toError(e), settled: true, busy: false }));
      } finally {
        inFlight = false;
        if (again && !cancelled) {
          again = false;
          void execute();
        } else {
          settleWaiters();
        }
      }
    };

    const schedule = () => {
      if (cancelled || intervalMs === null) return;
      timer = setTimeout(() => {
        timer = undefined;
        if (cancelled || isHidden()) return; // resumed by the visibilitychange handler
        void loop();
      }, intervalMs);
    };

    const loop = async () => {
      await execute();
      schedule();
    };

    const onVisibility = () => {
      if (cancelled || isHidden() || intervalMs === null) return;
      if (timer === undefined && !inFlight) void loop();
    };

    refreshRef.current = execute;
    void loop();
    document.addEventListener('visibilitychange', onVisibility);
    return () => {
      cancelled = true;
      if (timer !== undefined) clearTimeout(timer);
      document.removeEventListener('visibilitychange', onVisibility);
      settleWaiters();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [enabled, intervalMs, keepPrevious, ...deps]);

  return {
    data: state.data,
    error: state.error,
    loading: enabled && !state.settled,
    refreshing: state.busy && state.data !== undefined,
    updatedAt: state.updatedAt,
    paused: !visible && intervalMs !== null,
    refresh: () => refreshRef.current(),
  };
}

/** One-shot variant of {@link usePolling}: fetch on mount / deps change, refresh on demand. */
export function useFetch<T>(
  fetcher: () => Promise<T>,
  deps: DependencyList,
  options: Omit<PollingOptions, 'intervalMs'> = {},
): PollingResult<T> {
  return usePolling(fetcher, deps, { ...options, intervalMs: null });
}
