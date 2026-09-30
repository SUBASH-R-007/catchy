import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '../../api/client';
import type { StampedeRequest, StampedeResult, StressConfig, StressReport } from '../../api/rest';

export type RunStatus = 'idle' | 'running' | 'done' | 'error';

export interface DiagnosticRun<C, R> {
  status: RunStatus;
  /** The configuration of the current or last run. */
  config: C | null;
  /** The last successful result; cleared when a new run starts. */
  result: R | null;
  /** The last failure; cleared when a new run starts. */
  error: Error | null;
  /** Date.now() when the current or last run started. */
  startedAt: number;
  /**
   * Runs the diagnostic and resolves with its result (rejects with the ApiError on failure, e.g.
   * status 409 when the server is busy). Callable programmatically, e.g. by the guided demo.
   */
  run: (config: C) => Promise<R>;
}

interface RunState<C, R> {
  status: RunStatus;
  config: C | null;
  result: R | null;
  error: Error | null;
  startedAt: number;
}

/**
 * Tracks one blocking diagnostics call (stress test or stampede) at a time. A second run while one
 * is in flight rejects immediately without calling the server.
 */
export function useDiagnosticRun<C, R>(call: (config: C) => Promise<R>): DiagnosticRun<C, R> {
  const [state, setState] = useState<RunState<C, R>>({
    status: 'idle',
    config: null,
    result: null,
    error: null,
    startedAt: 0,
  });
  const inFlight = useRef(false);
  const mounted = useRef(true);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  const run = useCallback(
    async (config: C): Promise<R> => {
      if (inFlight.current) throw new Error('A run is already in progress.');
      inFlight.current = true;
      setState({ status: 'running', config, result: null, error: null, startedAt: Date.now() });
      try {
        const result = await call(config);
        if (mounted.current) setState((s) => ({ ...s, status: 'done', result }));
        return result;
      } catch (e) {
        const error = e instanceof Error ? e : new Error(String(e));
        if (mounted.current) setState((s) => ({ ...s, status: 'error', error }));
        throw error;
      } finally {
        inFlight.current = false;
      }
    },
    [call],
  );

  return { ...state, run };
}

/** POST /api/stress with progress state; `run(config)` resolves with the StressReport. */
export function useStressTest(): DiagnosticRun<StressConfig, StressReport> {
  return useDiagnosticRun(api.runStress);
}

/** POST /api/stress/stampede with progress state; `run(body)` resolves with the result. */
export function useStampedeTest(): DiagnosticRun<StampedeRequest, StampedeResult> {
  return useDiagnosticRun(api.runStampede);
}
