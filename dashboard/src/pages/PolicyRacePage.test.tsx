import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api, ApiError } from '../api/client';
import { cacheMetrics, snapshot } from '../api/fixtures';
import { MetricsContext } from '../api/metricsContext';
import type { CacheInfo } from '../api/rest';
import { appendSnapshots, EMPTY_HISTORY, type StreamHistory } from '../api/streamReducer';
import type { ConnectionState } from '../api/types';
import { ToastProvider } from '../components';
import { PolicyRacePage } from './PolicyRacePage';

const info = (name: string, group: string) => ({ name, group }) as CacheInfo;

function Location() {
  const location = useLocation();
  return <p data-testid="location">{location.search}</p>;
}

function renderPage(history: StreamHistory, url = '/race', state: ConnectionState = 'live') {
  return render(
    <ToastProvider>
      <MetricsContext.Provider value={{ state, history }}>
        <MemoryRouter initialEntries={[url]}>
          <Routes>
            <Route
              path="/race"
              element={
                <>
                  <PolicyRacePage />
                  <Location />
                </>
              }
            />
          </Routes>
        </MemoryRouter>
      </MetricsContext.Provider>
    </ToastProvider>,
  );
}

const CACHES = [
  cacheMetrics(),
  cacheMetrics({ name: 'lfu-A', policy: 'LFU', hitRateWindow10s: 0.86 }),
  cacheMetrics({ name: 'play-1', group: 'playground', hitRateWindow10s: 0.5 }),
];

const idle = appendSnapshots(EMPTY_HISTORY, [
  snapshot({ ts: 1000, simulation: null, events: [], caches: CACHES }),
  snapshot({
    ts: 1500,
    simulation: null,
    caches: CACHES,
    events: [
      { ts: 1400, cache: 'lru-A', key: 'k:older', cause: 'EXPIRED' },
      { ts: 1450, cache: 'lfu-A', key: 'k:newest', cause: 'EVICTED' },
      { ts: 1460, cache: 'play-1', key: 'k:other-group', cause: 'EXPLICIT' },
    ],
  }),
]);

describe('PolicyRacePage', () => {
  beforeEach(() => {
    vi.spyOn(api, 'listCaches').mockResolvedValue([
      info('lru-A', 'demo'),
      info('lfu-A', 'demo'),
      info('play-1', 'playground'),
    ]);
  });

  it('charts only the selected group and defaults to demo', async () => {
    renderPage(idle);
    expect(await screen.findByLabelText('Cache group')).toHaveValue('demo');
    const figure = screen.getByRole('figure');
    expect(within(figure).getByText(/lru-A \(LRU, solid line\)/)).toBeInTheDocument();
    expect(within(figure).queryByText(/play-1/)).not.toBeInTheDocument();
  });

  it('preselects the group from ?group= and updates the URL when changed', async () => {
    renderPage(idle, '/race?group=playground');
    const select = await screen.findByLabelText('Cache group');
    expect(select).toHaveValue('playground');
    expect(within(screen.getByRole('figure')).getAllByText(/play-1/).length).toBeGreaterThan(0);
    await userEvent.selectOptions(select, 'demo');
    expect(screen.getByTestId('location')).toHaveTextContent('?group=demo');
  });

  it('starts the chosen pattern with the fixed race parameters', async () => {
    const start = vi.spyOn(api, 'startSimulation').mockResolvedValue({ id: 's-1' });
    renderPage(idle);
    await userEvent.click(await screen.findByRole('radio', { name: /Scan pollution/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Start' }));
    expect(start).toHaveBeenCalledWith({
      group: 'demo',
      pattern: 'SCAN_POLLUTION',
      opsPerSec: 5000,
      readRatio: 0.9,
      durationSec: 300,
      seed: 42,
    });
    expect(await screen.findByText(/Started Scan pollution/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Stop' })).toBeDisabled();
  });

  it('describes all eight patterns', async () => {
    renderPage(idle);
    expect(await screen.findAllByRole('radio')).toHaveLength(8);
    expect(screen.getByText('A one-off scan of cold keys floods the cache.')).toBeInTheDocument();
  });

  it('shows the running simulation and stops it by id', async () => {
    const stop = vi.spyOn(api, 'stopSimulation').mockResolvedValue(undefined);
    renderPage(appendSnapshots(EMPTY_HISTORY, [snapshot({ ts: 1790000012000 })]));
    expect(await screen.findByText('Running Scan pollution on group “demo”')).toBeInTheDocument();
    expect(screen.getByText('0:12 elapsed')).toBeInTheDocument();
    expect(screen.getByText("A one-off scan floods the cache — watch LRU's line")).toBeVisible();
    expect(screen.getByRole('button', { name: /Switch to Zipf/ })).toBeEnabled();
    await userEvent.click(screen.getByRole('button', { name: 'Stop' }));
    expect(stop).toHaveBeenCalledWith('s-12');
  });

  it('toasts a failed start', async () => {
    vi.spyOn(api, 'startSimulation').mockRejectedValue(
      new ApiError("Group 'demo' has no caches", 404, null),
    );
    renderPage(idle);
    await userEvent.click(await screen.findByRole('button', { name: 'Start' }));
    expect(await screen.findByText("Group 'demo' has no caches")).toBeInTheDocument();
  });

  it("logs the group's removals newest first with cause text", async () => {
    renderPage(idle);
    const table = await screen.findByRole('table');
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent('k:newest');
    expect(rows[0]).toHaveTextContent('Evicted');
    expect(rows[1]).toHaveTextContent('Expired');
    expect(within(table).queryByText('k:other-group')).not.toBeInTheDocument();
  });

  it('shows an empty log when nothing was removed', async () => {
    renderPage(appendSnapshots(EMPTY_HISTORY, [snapshot({ simulation: null, events: [] })]));
    expect(await screen.findByText('No removals yet')).toBeInTheDocument();
  });

  it('shows loading skeletons while connecting', () => {
    vi.mocked(api.listCaches).mockReturnValue(new Promise(() => {}));
    renderPage(EMPTY_HISTORY, '/race', 'connecting');
    expect(screen.getByLabelText('Loading cache groups')).toBeInTheDocument();
    expect(screen.getAllByLabelText('Waiting for the first metrics').length).toBeGreaterThan(0);
  });

  it('shows error states when the server is unreachable', async () => {
    vi.mocked(api.listCaches).mockRejectedValue(new ApiError('Cannot reach', 0, null));
    renderPage(EMPTY_HISTORY, '/race', 'offline');
    await waitFor(() => expect(screen.getByText('Cannot load cache groups')).toBeInTheDocument());
    expect(screen.getAllByRole('alert').length).toBeGreaterThan(1);
  });

  it('explains a group without caches', async () => {
    renderPage(idle, '/race?group=ghost');
    expect(await screen.findAllByText('Group “ghost” has no caches')).toHaveLength(2);
  });
});
