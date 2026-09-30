import { act, render, renderHook, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { useTheme } from '../hooks/useTheme';
import { makeRegion, makeSummary } from '../test/fixtures';
import { ApplicationCard } from './ApplicationCard';
import { LiveIndicator } from './LiveIndicator';
import { Modal } from './Modal';
import { RegionTable } from './RegionTable';

function renderTable(regions = tableRegions, extra: Partial<Parameters<typeof RegionTable>[0]> = {}) {
  return render(
    <MemoryRouter>
      <RegionTable regions={regions} {...extra} />
    </MemoryRouter>,
  );
}

const tableRegions = [
  makeRegion({ applicationId: 1, applicationName: 'claims-service', cacheRegion: 'claim-rules', riskLevel: 'MEDIUM', hitRate: 91.4, evictions: 120 }),
  makeRegion({ applicationId: 1, applicationName: 'claims-service', cacheRegion: 'claim-lookup', riskLevel: 'LOW', hitRate: 96.3, evictions: 5 }),
  makeRegion({ applicationId: 2, applicationName: 'eligibility-service', cacheRegion: 'member-eligibility', riskLevel: 'HIGH', hitRate: 48.2, evictions: 900, health: { status: 'WARNING', score: 40, reasons: ['x'] } }),
  makeRegion({ applicationId: 3, applicationName: 'authorization-service', cacheRegion: 'authorization-decision', riskLevel: 'CRITICAL', hitRate: 79, evictions: 60 }),
];

function rowNames(): string[] {
  const table = screen.getByRole('table');
  return within(table)
    .getAllByRole('row')
    .slice(1)
    .map((row) => within(row).getAllByRole('link')[0]?.textContent ?? '');
}

describe('RegionTable', () => {
  it('shows every required column', () => {
    renderTable();
    const headers = within(screen.getByRole('table')).getAllByRole('columnheader').map((h) => h.textContent?.trim());
    for (const name of [
      'Region name',
      'Application',
      'Risk level',
      'Active policy',
      'Hit rate',
      'Miss rate',
      'Current entries',
      'Estimated memory',
      'Memory utilization',
      'Evictions',
      'Expirations',
      'Health status',
      'Last updated',
    ]) {
      expect(headers).toContain(name);
    }
  });

  it('defaults to highest risk first and re-sorts with the labelled select', async () => {
    const user = userEvent.setup();
    renderTable();
    expect(rowNames()).toEqual(['authorization-decision', 'member-eligibility', 'claim-rules', 'claim-lookup']);
    await user.selectOptions(screen.getByLabelText('Sort by'), 'Lowest hit rate');
    expect(rowNames()).toEqual(['member-eligibility', 'authorization-decision', 'claim-rules', 'claim-lookup']);
    await user.selectOptions(screen.getByLabelText('Sort by'), 'Most evictions');
    expect(rowNames()[0]).toBe('member-eligibility');
  });

  it('sorts from keyboard-operable column headers and toggles hit-rate direction', async () => {
    const user = userEvent.setup();
    renderTable();
    const hitHeader = screen.getByRole('columnheader', { name: /Hit rate/ });
    const button = within(hitHeader).getByRole('button');
    await user.click(button);
    expect(rowNames()[0]).toBe('claim-lookup');
    expect(hitHeader).toHaveAttribute('aria-sort', 'descending');
    button.focus();
    await user.keyboard('{Enter}');
    expect(rowNames()[0]).toBe('member-eligibility');
    expect(hitHeader).toHaveAttribute('aria-sort', 'ascending');
    expect(screen.getByLabelText('Sort by')).toHaveValue('hit-asc');
  });

  it('filters by application, risk level and region name', async () => {
    const user = userEvent.setup();
    renderTable();
    await user.selectOptions(screen.getByLabelText('Application'), 'claims-service (staging)');
    expect(rowNames().sort()).toEqual(['claim-lookup', 'claim-rules']);
    await user.selectOptions(screen.getByLabelText('Risk level'), 'LOW');
    expect(rowNames()).toEqual(['claim-lookup']);
    expect(screen.getByText('Showing 1 of 4 regions')).toBeInTheDocument();
    await user.click(screen.getAllByRole('button', { name: 'Clear filters' })[0] as HTMLElement);
    expect(rowNames()).toHaveLength(4);
    await user.type(screen.getByLabelText('Search region name'), 'ELIGIB');
    expect(rowNames()).toEqual(['member-eligibility']);
  });

  it('offers to clear filters when nothing matches', async () => {
    const user = userEvent.setup();
    renderTable();
    await user.type(screen.getByLabelText('Search region name'), 'zzz');
    expect(screen.getByText('No regions match these filters')).toBeInTheDocument();
  });

  it('has loading, error and empty states', async () => {
    const { rerender } = render(
      <MemoryRouter>
        <RegionTable regions={undefined} loading />
      </MemoryRouter>,
    );
    expect(screen.getByText('Loading cache regions…')).toBeInTheDocument();
    const retry = vi.fn();
    rerender(
      <MemoryRouter>
        <RegionTable regions={undefined} error={new Error('nope')} onRetry={retry} />
      </MemoryRouter>,
    );
    expect(screen.getByText('Could not load cache regions')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(retry).toHaveBeenCalled();
    rerender(
      <MemoryRouter>
        <RegionTable regions={[]} />
      </MemoryRouter>,
    );
    expect(screen.getByText('No cache regions reported yet')).toBeInTheDocument();
    expect(screen.getByText(/start a demo service or run a simulation/i)).toBeInTheDocument();
  });

  it('hides the application column and filter for a single application', () => {
    renderTable(tableRegions.slice(0, 2), { singleApplication: true });
    expect(screen.queryByLabelText('Application')).toBeNull();
    expect(screen.queryByRole('columnheader', { name: 'Application' })).toBeNull();
  });
});

describe('ApplicationCard', () => {
  it('shows health, hit rate, memory, policy, recommendation and freshness', () => {
    render(
      <MemoryRouter>
        <ApplicationCard summary={makeSummary({ lastTelemetryAt: new Date(Date.now() - 3000).toISOString() })} />
      </MemoryRouter>,
    );
    expect(screen.getByRole('link', { name: 'Claims Service' })).toHaveAttribute('href', '/applications/1');
    expect(screen.getByText(/Environment: Staging/)).toBeInTheDocument();
    expect(screen.getByText('Good')).toBeInTheDocument();
    expect(screen.getByText('91.40%')).toBeInTheDocument();
    expect(screen.getByText('132 MB / 256 MB')).toBeInTheDocument();
    expect(screen.getByText('Keep LFU')).toBeInTheDocument();
    expect(screen.getByText(/3 s ago/)).toBeInTheDocument();
  });

  it('handles an application that never sent telemetry', () => {
    render(
      <MemoryRouter>
        <ApplicationCard summary={makeSummary({ lastTelemetryAt: null, secondsSinceLastTelemetry: null, health: { status: 'UNKNOWN', score: 0, reasons: ['No telemetry received yet.'] }, recommendationSummary: 'No data yet' })} />
      </MemoryRouter>,
    );
    expect(screen.getByText('No telemetry yet')).toBeInTheDocument();
    expect(screen.getByText('Unknown')).toBeInTheDocument();
    expect(screen.getByText(/never/)).toBeInTheDocument();
    expect(screen.queryByText('Hit rate')).toBeNull();
  });
});

describe('LiveIndicator', () => {
  it('reads "Live • updated N s ago" and announces only state changes', () => {
    const { rerender } = render(<LiveIndicator updatedAt={Date.now() - 2000} />);
    expect(screen.getByText(/Live • updated 2 s ago/)).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Live updates active');
    rerender(<LiveIndicator updatedAt={Date.now() - 2000} paused />);
    expect(screen.getByText(/Paused/)).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent(/paused/i);
    rerender(<LiveIndicator updatedAt={Date.now() - 9000} error={new Error('x')} />);
    expect(screen.getAllByText(/Connection problem/).length).toBeGreaterThan(0);
    expect(screen.getByRole('status')).toHaveTextContent(/Connection problem: showing the last data received/);
  });
});

describe('Modal', () => {
  it('closes on Escape, traps focus and returns focus to the trigger', async () => {
    const user = userEvent.setup();
    function ModalTrigger() {
      const [open, setOpen] = useState(false);
      return (
        <>
          <button onClick={() => setOpen(true)}>Open</button>
          <Modal open={open} title="Confirm" onClose={() => setOpen(false)} footer={<button>Second</button>}>
            <button>First</button>
          </Modal>
        </>
      );
    }
    render(<ModalTrigger />);
    const trigger = screen.getByRole('button', { name: 'Open' });
    await user.click(trigger);
    const dialog = screen.getByRole('dialog', { name: 'Confirm' });
    expect(dialog).toHaveAttribute('aria-modal', 'true');
    expect(within(dialog).getByRole('button', { name: 'Close dialog' })).toHaveFocus();
    await user.tab();
    expect(within(dialog).getByRole('button', { name: 'First' })).toHaveFocus();
    await user.tab();
    expect(within(dialog).getByRole('button', { name: 'Second' })).toHaveFocus();
    await user.tab();
    expect(within(dialog).getByRole('button', { name: 'Close dialog' })).toHaveFocus();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(trigger).toHaveFocus();
  });
});


describe('useTheme', () => {
  it('toggles, stamps data-theme and persists the choice', () => {
    const { result } = renderHook(() => useTheme());
    const initial = result.current.theme;
    act(() => result.current.toggle());
    expect(result.current.theme).toBe(initial === 'dark' ? 'light' : 'dark');
    expect(document.documentElement.getAttribute('data-theme')).toBe(result.current.theme);
    expect(window.localStorage.getItem('catchy.theme')).toBe(result.current.theme);
  });

  it('still works when localStorage throws', () => {
    const set = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    const get = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    const { result } = renderHook(() => useTheme());
    expect(() => act(() => result.current.toggle())).not.toThrow();
    expect(['light', 'dark']).toContain(result.current.theme);
    set.mockRestore();
    get.mockRestore();
  });
});
