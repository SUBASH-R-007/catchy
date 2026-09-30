import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { cacheMetrics, snapshot } from '../api/fixtures';
import { MetricsContext } from '../api/metricsContext';
import { appendSnapshots, EMPTY_HISTORY, type StreamHistory } from '../api/streamReducer';
import type { ConnectionState } from '../api/types';
import { ToastProvider } from '../components';
import { OverviewPage } from './OverviewPage';

function renderWith(state: ConnectionState, history: StreamHistory) {
  return render(
    <ToastProvider>
      <MetricsContext.Provider value={{ state, history }}>
        <OverviewPage />
      </MetricsContext.Provider>
    </ToastProvider>,
  );
}

const twoTicks = appendSnapshots(EMPTY_HISTORY, [
  snapshot({ ts: 1_000, simulation: null }),
  snapshot({
    ts: 1_500,
    simulation: null,
    caches: [
      cacheMetrics({ evictions: 180_102 + 400 }),
      cacheMetrics({ name: 'lfu-A', policy: 'LFU', hitRate: 0.84, hitRateWindow10s: 0.86 }),
    ],
  }),
]);

describe('OverviewPage', () => {
  it('shows a loading skeleton while connecting', () => {
    renderWith('connecting', EMPTY_HISTORY);
    expect(screen.getAllByLabelText('Waiting for the first metrics').length).toBeGreaterThan(0);
  });

  it('explains how to start the server when the stream is offline', () => {
    renderWith('offline', EMPTY_HISTORY);
    expect(screen.getAllByRole('alert')[0]).toHaveTextContent('Cannot reach the metrics stream');
  });

  it('shows KPI tiles for the first cache and derives per-second evictions', () => {
    renderWith('live', twoTicks);
    const hit = screen.getByRole('group', { name: /Hit rate/ });
    expect(hit).toHaveTextContent('77.4%');
    expect(screen.getByRole('group', { name: /Miss rate/ })).toHaveTextContent('22.6%');
    expect(screen.getByRole('group', { name: /Evictions/ })).toHaveTextContent('800/s now');
    expect(screen.getByRole('group', { name: /p99 get/ })).toHaveTextContent('6.4 µs');
  });

  it('switches tiles when another cache is selected', async () => {
    renderWith('live', twoTicks);
    await userEvent.selectOptions(screen.getByLabelText('Cache'), 'lfu-A');
    expect(screen.getByRole('group', { name: /Hit rate/ })).toHaveTextContent('86.0%');
  });

  it('describes the chart in text for screen readers', () => {
    renderWith('live', twoTicks);
    const figure = screen.getAllByRole('figure')[0] as HTMLElement;
    expect(within(figure).getByText(/lru-A \(LRU, solid line\): 77\.4% now/)).toBeInTheDocument();
    expect(within(figure).getByText(/lfu-A \(LFU, dashed line\): 86\.0% now/)).toBeInTheDocument();
  });

  it('summarises removals by cause per cache', () => {
    renderWith('live', twoTicks);
    expect(screen.getByText(/lru-A: 800\/s evictions and 0\/s expirations\./)).toBeInTheDocument();
    expect(
      screen.getByText(/lru-A removes the most entries, mostly evictions/),
    ).toBeInTheDocument();
  });

  it('shows an empty state when the server has no caches', () => {
    renderWith('live', appendSnapshots(EMPTY_HISTORY, [snapshot({ caches: [], groups: [] })]));
    expect(screen.getByText('No caches yet')).toBeInTheDocument();
  });
});
