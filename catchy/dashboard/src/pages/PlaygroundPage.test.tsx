import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, ApiError } from '../api/client';
import type { CacheInfo, EntryView } from '../api/rest';
import { ToastProvider } from '../components';
import { PlaygroundPage } from './PlaygroundPage';
import { EntriesTable } from './playground/EntriesTable';

function cacheInfo(overrides: Partial<CacheInfo> = {}): CacheInfo {
  return {
    name: 'c1',
    policy: 'LRU',
    capacity: 5,
    defaultTtlMs: null,
    concurrencyLevel: 1,
    group: 'playground',
    size: 2,
    hits: 3,
    misses: 1,
    hitRate: 0.75,
    missRate: 0.25,
    evictions: 4,
    expirations: 1,
    puts: 6,
    ...overrides,
  };
}

const ENTRIES: EntryView[] = [
  { key: 'short', frequency: 0, ttlRemainingMs: 5000 },
  { key: 'forever', frequency: 0, ttlRemainingMs: null },
];

function renderPage() {
  return render(
    <ToastProvider>
      <PlaygroundPage />
    </ToastProvider>,
  );
}

describe('PlaygroundPage', () => {
  beforeEach(() => {
    vi.spyOn(api, 'listCaches').mockResolvedValue([cacheInfo()]);
    vi.spyOn(api, 'listEntries').mockResolvedValue(ENTRIES);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('shows empty states with a call to action when there are no caches', async () => {
    vi.mocked(api.listCaches).mockResolvedValue([]);
    renderPage();
    expect(await screen.findAllByText('No caches yet')).toHaveLength(3);
    await userEvent.click(screen.getAllByRole('button', { name: 'Create a cache' })[0]!);
    expect(screen.getByLabelText('Name')).toHaveFocus();
  });

  it('shows an error state when the server is unreachable', async () => {
    vi.mocked(api.listCaches).mockRejectedValue(
      new ApiError('Cannot reach the CacheLab server. Is it running on port 8080?', 0, null),
    );
    renderPage();
    const alerts = await screen.findAllByRole('alert');
    expect(alerts[0]).toHaveTextContent('Cannot reach the CacheLab server');
    expect(screen.getAllByRole('button', { name: 'Retry' }).length).toBeGreaterThan(0);
  });

  it('creates a cache with the right body and selects it', async () => {
    let server: CacheInfo[] = [];
    vi.mocked(api.listCaches).mockImplementation(() => Promise.resolve(server));
    const created = {
      name: 'demo-1',
      policy: 'LFU',
      capacity: 5,
      defaultTtlMs: 30_000,
      concurrencyLevel: 1,
      group: 'playground',
    } as const;
    const create = vi.spyOn(api, 'createCache').mockImplementation(() => {
      server = [cacheInfo({ ...created, size: 0 })];
      return Promise.resolve(created);
    });
    renderPage();
    await screen.findAllByText('No caches yet');

    const nameInput = screen.getByLabelText('Name');
    await userEvent.clear(nameInput);
    await userEvent.type(nameInput, 'demo-1');
    const policy = screen.getByRole('radiogroup', { name: 'Eviction policy' });
    await userEvent.click(within(policy).getByRole('radio', { name: 'LFU' }));
    expect(screen.getByLabelText('Capacity (entries)')).toHaveValue(5);
    await userEvent.type(screen.getByLabelText('Default TTL (s, optional)'), '30');
    await userEvent.click(screen.getByRole('button', { name: 'Create cache' }));

    expect(create).toHaveBeenCalledWith({
      name: 'demo-1',
      policy: 'LFU',
      capacity: 5,
      defaultTtlMs: 30_000,
      group: 'playground',
    });
    expect(await screen.findByText('Created cache "demo-1".')).toBeInTheDocument();
    expect(screen.getByLabelText('Cache')).toHaveValue('demo-1');
    expect(screen.getByLabelText('Name')).toHaveValue('demo-2');
  });

  it('omits the default TTL when it is left empty', async () => {
    const create = vi.spyOn(api, 'createCache').mockResolvedValue({
      name: 'play-1',
      policy: 'LRU',
      capacity: 5,
      defaultTtlMs: null,
      concurrencyLevel: 1,
      group: 'playground',
    });
    renderPage();
    await userEvent.click(screen.getByRole('button', { name: 'Create cache' }));
    expect(create).toHaveBeenCalledWith({
      name: 'play-1',
      policy: 'LRU',
      capacity: 5,
      group: 'playground',
    });
  });

  it('shows HIT and MISS results and keeps a history', async () => {
    const get = vi
      .spyOn(api, 'getEntry')
      .mockResolvedValueOnce({ hit: true, value: 'v1', ttlRemainingMs: 4000 })
      .mockResolvedValueOnce({ hit: false, value: null, ttlRemainingMs: null });
    renderPage();
    await userEvent.type(await screen.findByLabelText('Key'), 'k1');

    await userEvent.click(screen.getByRole('button', { name: 'Get' }));
    const result = screen.getByRole('status', { name: 'Result' });
    await waitFor(() => expect(result).toHaveTextContent('HIT — value "v1", expires in 4.0 s.'));
    expect(get).toHaveBeenCalledWith('c1', 'k1');

    await userEvent.click(screen.getByRole('button', { name: 'Get' }));
    await waitFor(() =>
      expect(result).toHaveTextContent('MISS — the key is not in the cache (or it expired).'),
    );
    const rows = screen.getAllByRole('row').filter((r) => within(r).queryByText('k1'));
    expect(rows.map((r) => r.textContent)).toEqual([
      expect.stringContaining('MISS'),
      expect.stringContaining('HIT'),
    ]);
  });

  it('puts with a TTL and reports delete outcomes', async () => {
    const put = vi.spyOn(api, 'putEntry').mockResolvedValue(undefined);
    vi.spyOn(api, 'deleteEntry').mockResolvedValue({ removed: false });
    renderPage();
    await userEvent.type(await screen.findByLabelText('Key'), 'k2');
    await userEvent.type(screen.getByLabelText('Value (for put)'), 'hello');
    await userEvent.type(screen.getByLabelText('TTL for put (s, optional)'), '2');
    await userEvent.click(screen.getByRole('button', { name: 'Put' }));
    expect(put).toHaveBeenCalledWith('c1', 'k2', { value: 'hello', ttlMs: 2000 });
    const result = screen.getByRole('status', { name: 'Result' });
    await waitFor(() => expect(result).toHaveTextContent('Stored "k2"; it expires in 2.0 s.'));

    await userEvent.click(screen.getByRole('button', { name: 'Delete' }));
    await waitFor(() => expect(result).toHaveTextContent('Not found'));
  });

  it('shows a toast with the server message when a command fails', async () => {
    vi.spyOn(api, 'getEntry').mockRejectedValue(new ApiError("No cache named 'c1'", 404, null));
    renderPage();
    await userEvent.type(await screen.findByLabelText('Key'), 'k1');
    await userEvent.click(screen.getByRole('button', { name: 'Get' }));
    expect(await screen.findByText("No cache named 'c1'")).toBeInTheDocument();
  });

  it('renders entries with a countdown and "never"', async () => {
    renderPage();
    expect(await screen.findByText('forever')).toBeInTheDocument();
    const row = screen.getByText('short').closest('tr')!;
    expect(row).toHaveTextContent(/\d\.\d s/);
    expect(screen.getByText('forever').closest('tr')).toHaveTextContent('never');
    expect(api.listEntries).toHaveBeenCalledWith('c1', 50);
  });

  it('switches the policy live through the endpoint', async () => {
    const sw = vi.spyOn(api, 'switchPolicy').mockImplementation(() => {
      vi.mocked(api.listCaches).mockResolvedValue([cacheInfo({ policy: 'LFU_DECAY' })]);
      return Promise.resolve({ ...cacheInfo(), policy: 'LFU_DECAY' });
    });
    renderPage();
    const group = await screen.findByRole('radiogroup', { name: 'Policy (live switch)' });
    await userEvent.click(within(group).getByRole('radio', { name: 'LFU decay' }));
    expect(sw).toHaveBeenCalledWith('c1', 'LFU_DECAY');
    await waitFor(() =>
      expect(within(group).getByRole('radio', { name: 'LFU decay' })).toBeChecked(),
    );
  });

  it('snaps the policy switch back and shows a toast on error', async () => {
    vi.spyOn(api, 'switchPolicy').mockRejectedValue(
      new ApiError('Policy switch failed', 400, null),
    );
    renderPage();
    const group = await screen.findByRole('radiogroup', { name: 'Policy (live switch)' });
    await userEvent.click(within(group).getByRole('radio', { name: 'LFU' }));
    expect(await screen.findByText('Policy switch failed')).toBeInTheDocument();
    expect(within(group).getByRole('radio', { name: 'LRU' })).toBeChecked();
  });

  it('deletes a cache only after the inline confirmation', async () => {
    const del = vi.spyOn(api, 'deleteCache').mockResolvedValue(undefined);
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: 'Delete cache' }));
    expect(del).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'Cancel' })).toHaveFocus();
    await userEvent.keyboard('{Escape}');
    expect(screen.getByRole('button', { name: 'Delete cache' })).toHaveFocus();

    await userEvent.click(screen.getByRole('button', { name: 'Delete cache' }));
    await userEvent.click(screen.getByRole('button', { name: 'Yes, delete' }));
    expect(del).toHaveBeenCalledWith('c1');
  });

  it('resets stats', async () => {
    const reset = vi.spyOn(api, 'resetStats').mockResolvedValue(undefined);
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: 'Reset stats' }));
    expect(reset).toHaveBeenCalledWith('c1');
    expect(await screen.findByText('Stats reset for "c1".')).toBeInTheDocument();
  });
});

describe('EntriesTable', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(1_000_000);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('counts down smoothly between polls and shows "expired" at 0', () => {
    render(<EntriesTable entries={ENTRIES} fetchedAt={Date.now()} policy="LFU" />);
    const row = () => screen.getByText('short').closest('tr')!;
    expect(row()).toHaveTextContent('5.0 s');
    act(() => vi.advanceTimersByTime(1500));
    expect(row()).toHaveTextContent('3.5 s');
    act(() => vi.advanceTimersByTime(4000));
    expect(row()).toHaveTextContent('expired');
    expect(screen.getByText('forever').closest('tr')).toHaveTextContent('never');
  });

  it('hides frequency under LRU', () => {
    render(
      <EntriesTable
        entries={[{ key: 'a', frequency: 0, ttlRemainingMs: null }]}
        fetchedAt={Date.now()}
        policy="LRU"
      />,
    );
    expect(screen.getByText('a').closest('tr')).toHaveTextContent('—');
    expect(screen.getByText('not tracked under LRU')).toBeInTheDocument();
  });

  it('shows frequency under LFU', () => {
    render(
      <EntriesTable
        entries={[{ key: 'a', frequency: 7, ttlRemainingMs: null }]}
        fetchedAt={Date.now()}
        policy="LFU"
      />,
    );
    expect(screen.getByText('a').closest('tr')).toHaveTextContent('7');
  });
});
