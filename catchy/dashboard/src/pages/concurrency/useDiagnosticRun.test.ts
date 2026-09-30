import { act, renderHook, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { api, ApiError } from '../../api/client';
import type { StressReport } from '../../api/rest';
import { useDiagnosticRun, useStressTest } from './useDiagnosticRun';

describe('useDiagnosticRun', () => {
  it('runs the call and exposes the result', async () => {
    const report = { impl: 'SINGLE_LOCK', threads: 4 } as StressReport;
    const spy = vi.spyOn(api, 'runStress').mockResolvedValue(report);
    const { result } = renderHook(() => useStressTest());
    expect(result.current.status).toBe('idle');
    let returned: StressReport | undefined;
    await act(async () => {
      returned = await result.current.run({ impl: 'SINGLE_LOCK', threads: 4, durationMs: 1000 });
    });
    expect(returned).toBe(report);
    expect(spy).toHaveBeenCalledWith({ impl: 'SINGLE_LOCK', threads: 4, durationMs: 1000 });
    expect(result.current).toMatchObject({ status: 'done', result: report, error: null });
  });

  it('shows running while the call is in flight and rejects a second run', async () => {
    let resolve: (value: number) => void = () => {};
    const call = vi.fn(() => new Promise<number>((r) => (resolve = r)));
    const { result } = renderHook(() => useDiagnosticRun(call));
    let first: Promise<number> | undefined;
    act(() => {
      first = result.current.run(1);
    });
    expect(result.current.status).toBe('running');
    await expect(result.current.run(2)).rejects.toThrow('already in progress');
    expect(call).toHaveBeenCalledTimes(1);
    await act(async () => {
      resolve(42);
      await first;
    });
    expect(result.current.result).toBe(42);
  });

  it('records and rethrows failures', async () => {
    const error = new ApiError('busy', 409, null);
    const { result } = renderHook(() => useDiagnosticRun(() => Promise.reject(error)));
    await act(async () => {
      await expect(result.current.run(undefined)).rejects.toBe(error);
    });
    await waitFor(() => expect(result.current.status).toBe('error'));
    expect(result.current.error).toBe(error);
  });
});
