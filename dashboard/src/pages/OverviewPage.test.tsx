import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';
import { cacheMetrics, snapshot } from '../api/fixtures';
import { MetricsContext } from '../api/metricsContext';
import { appendSnapshots, EMPTY_HISTORY, type StreamHistory } from '../api/streamReducer';
import type { ConnectionState } from '../api/types';
import { ToastProvider } from '../components';
import { OverviewPage } from './OverviewPage';
import { PRICE_STORAGE_KEY } from './overview/cost';

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
  describe('cost panel', () => {
    afterEach(() => {
      window.localStorage.clear();
    });

    const panel = () => screen.getByRole('region', { name: 'Savings (estimates)' });

    it('shows calls avoided, latency saved and cost at the default price, marked as estimates', () => {
      renderWith('live', twoTicks);
      const cost = panel();
      expect(within(cost).getByText('Estimate')).toBeInTheDocument();
      expect(within(cost).getByRole('group', { name: /DB calls avoided/ })).toHaveTextContent(
        '812.3K',
      );
      expect(within(cost).getByRole('group', { name: /Latency saved/ })).toHaveTextContent('2.7 h');
      expect(within(cost).getByRole('group', { name: /Cost saved/ })).toHaveTextContent('$40.62');
      expect(
        within(cost).getByRole('button', { name: 'About Savings (estimates)' }),
      ).toBeInTheDocument();
    });

    it('recomputes the cost from an edited price and remembers it', async () => {
      renderWith('live', twoTicks);
      const input = within(panel()).getByLabelText('Price per 1,000 DB calls ($)');
      await userEvent.clear(input);
      await userEvent.type(input, '1');
      expect(within(panel()).getByRole('group', { name: /Cost saved/ })).toHaveTextContent(
        '$812.34',
      );
      expect(window.localStorage.getItem(PRICE_STORAGE_KEY)).toBe('1');
    });

    it('keeps the last valid price while the input is invalid', async () => {
      renderWith('live', twoTicks);
      const input = within(panel()).getByLabelText('Price per 1,000 DB calls ($)');
      await userEvent.clear(input);
      expect(input).toHaveAttribute('aria-invalid', 'true');
      expect(within(panel()).getByText(/Still using \$0\.05/)).toBeInTheDocument();
      expect(within(panel()).getByRole('group', { name: /Cost saved/ })).toHaveTextContent(
        '$40.62',
      );
    });

    it('follows the cache selected on the Overview', async () => {
      window.localStorage.setItem(PRICE_STORAGE_KEY, '0.1');
      const history = appendSnapshots(EMPTY_HISTORY, [
        snapshot({
          caches: [cacheMetrics(), cacheMetrics({ name: 'lfu-A', dbCallsAvoided: 2000 })],
        }),
      ]);
      renderWith('live', history);
      await userEvent.selectOptions(screen.getByLabelText('Cache'), 'lfu-A');
      expect(within(panel()).getByRole('group', { name: /Cost saved/ })).toHaveTextContent('$0.20');
    });
  });
});
