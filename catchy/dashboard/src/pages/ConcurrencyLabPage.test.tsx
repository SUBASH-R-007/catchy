import { act, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { api, ApiError } from '../api/client';
import type { StampedeResult, StressReport } from '../api/rest';
import { ToastProvider } from '../components';
import { ConcurrencyLabPage } from './ConcurrencyLabPage';
import { INVARIANT_NAMES } from './concurrency/stress';

function renderPage() {
  return render(
    <ToastProvider>
      <ConcurrencyLabPage />
    </ToastProvider>,
  );
}

const REPORT: StressReport = {
  impl: 'SEGMENTED',
  threads: 32,
  durationMs: 5004,
  totalOps: 12_000_000,
  opsPerSec: 2_398_081,
  invariants: INVARIANT_NAMES.map((name) => ({ name, passed: true, detail: 'ok' })),
  exceptions: [],
  deadlockFree: true,
};

describe('ConcurrencyLabPage', () => {
  it('shows the five invariants and empty states before any run', () => {
    renderPage();
    const leds = screen.getByRole('list', { name: 'Stress test invariants' });
    expect(within(leds).getAllByRole('listitem')).toHaveLength(5);
    expect(screen.getAllByText('Not run yet')).toHaveLength(2);
  });

  it('runs the stress test with the chosen settings and shows the report', async () => {
    let resolve: (r: StressReport) => void = () => {};
    const run = vi
      .spyOn(api, 'runStress')
      .mockImplementation(() => new Promise((r) => (resolve = r)));
    renderPage();
    await userEvent.click(screen.getByRole('radio', { name: 'Segmented' }));
    await userEvent.click(screen.getByRole('button', { name: 'Run stress test' }));
    expect(run).toHaveBeenCalledWith({ impl: 'SEGMENTED', threads: 32, durationMs: 5000 });
    expect(screen.getByRole('button', { name: 'Running…' })).toBeDisabled();
    expect(screen.getByRole('progressbar', { name: 'Stress test progress' })).toBeInTheDocument();

    await act(async () => resolve(REPORT));
    expect(
      await screen.findByText('All 5 invariants held with 32 threads on the segmented cache.'),
    ).toBeInTheDocument();
    expect(screen.getByText('12,000,000')).toBeInTheDocument();
    expect(screen.getByText('2,398,081 ops/s')).toBeInTheDocument();
    expect(screen.getByText('No deadlocks detected.')).toBeInTheDocument();
  });

  it('uses the sliders for threads and duration', async () => {
    const run = vi.spyOn(api, 'runStress').mockResolvedValue(REPORT);
    renderPage();
    // jsdom does not implement range keyboard input; set the values directly.
    fireEvent.change(screen.getByRole('slider', { name: /Threads/ }), { target: { value: '1' } });
    fireEvent.change(screen.getByRole('slider', { name: /Duration/ }), { target: { value: '10' } });
    expect(screen.getByRole('slider', { name: /Threads/ })).toHaveAttribute(
      'aria-valuetext',
      '1 thread',
    );
    await userEvent.click(screen.getByRole('button', { name: 'Run stress test' }));
    expect(run).toHaveBeenCalledWith({ impl: 'SINGLE_LOCK', threads: 1, durationMs: 10_000 });
  });

  it('lists exceptions and a deadlock as failures', async () => {
    vi.spyOn(api, 'runStress').mockResolvedValue({
      ...REPORT,
      invariants: INVARIANT_NAMES.map((name, i) => ({ name, passed: i < 4, detail: 'boom' })),
      exceptions: ['java.lang.IllegalStateException: corrupt list'],
      deadlockFree: false,
    });
    renderPage();
    await userEvent.click(screen.getByRole('button', { name: 'Run stress test' }));
    expect(await screen.findByText(/1 of 5 invariants failed \(No exceptions\)/)).toBeVisible();
    expect(screen.getByText('java.lang.IllegalStateException: corrupt list')).toBeVisible();
    expect(screen.getByText(/Deadlock detected/)).toBeVisible();
  });

  it('toasts when the server is busy', async () => {
    vi.spyOn(api, 'runStress').mockRejectedValue(new ApiError('busy', 409, null));
    renderPage();
    await userEvent.click(screen.getByRole('button', { name: 'Run stress test' }));
    expect(await screen.findByText(/Another stress job is running/)).toBeInTheDocument();
    expect(screen.queryByText('The stress test did not complete')).not.toBeInTheDocument();
  });

  it('shows an error state with retry for other failures', async () => {
    vi.spyOn(api, 'runStress').mockRejectedValue(new ApiError('Cannot reach', 0, null));
    renderPage();
    await userEvent.click(screen.getByRole('button', { name: 'Run stress test' }));
    expect(await screen.findByText('The stress test did not complete')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeEnabled();
  });

  it('runs the stampede test and reports a single loader call', async () => {
    const result: StampedeResult = {
      threads: 200,
      loaderCalls: 1,
      allSameValue: true,
      durationMs: 214,
    };
    const run = vi.spyOn(api, 'runStampede').mockResolvedValue(result);
    renderPage();
    await userEvent.click(screen.getByRole('button', { name: 'Run stampede' }));
    expect(run).toHaveBeenCalledWith({ threads: 200, loaderDelayMs: 200 });
    const sentence = await screen.findByText(/200 threads asked for the same missing key/);
    expect(sentence).toHaveTextContent(
      '200 threads asked for the same missing key → loader ran 1 time.',
    );
    expect(screen.getByText(/All threads got the same value\. Took 214 ms\./)).toBeVisible();
    expect(screen.getByText('Single-flight held: no stampede.')).toBeVisible();
  });
});
