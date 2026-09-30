import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '../api/endpoints';
import { emptyOverview, makeOverview, makeTimeline } from '../test/fixtures';
import { renderWithProviders } from '../test/utils';
import { OverviewPage } from './OverviewPage';

beforeEach(() => {
  vi.restoreAllMocks();
  vi.spyOn(api, 'globalTimeline').mockResolvedValue(makeTimeline(12));
});

function tile(label: string) {
  return screen.getByRole('group', { name: label });
}

describe('OverviewPage: KPIs from a fixture', () => {
  it('renders all eight KPI tiles with formatted values', async () => {
    vi.spyOn(api, 'overview').mockResolvedValue(makeOverview());
    renderWithProviders(<OverviewPage />, { role: 'ENGINEER' });
    expect(await screen.findByRole('group', { name: 'Projects monitored' })).toBeInTheDocument();
    expect(within(tile('Projects monitored')).getByText('3')).toBeInTheDocument();
    expect(within(tile('Applications monitored')).getByText('2')).toBeInTheDocument();
    expect(within(tile('Cache regions monitored')).getByText('2')).toBeInTheDocument();
    expect(within(tile('Overall hit rate')).getByText('91.40%')).toBeInTheDocument();
    expect(within(tile('Overall miss rate')).getByText('8.60%')).toBeInTheDocument();
    const memory = tile('Total estimated memory usage');
    expect(within(memory).getByText('132 MB')).toBeInTheDocument();
    expect(within(memory).getByText(/of 256 MB limit/)).toBeInTheDocument();
    expect(within(tile('Total source calls avoided')).getByText('1.3M')).toBeInTheDocument();
    const alerts = tile('Active alerts');
    expect(within(alerts).getByText('2')).toBeInTheDocument();
    expect(within(alerts).getByText('1 critical')).toBeInTheDocument();
  });

  it('lists alerts, recommendations, application cards and the region table', async () => {
    vi.spyOn(api, 'overview').mockResolvedValue(makeOverview());
    renderWithProviders(<OverviewPage />, { role: 'VIEWER' });
    expect(await screen.findByText('Hit rate is 48.2%, below 65% target.')).toBeInTheDocument();
    expect(screen.getByText('Memory utilization is 99%.')).toBeInTheDocument();
    // alert links to the region page
    const alertLink = screen.getAllByRole('link', { name: 'claims-service / claim-rules' })[0];
    expect(alertLink).toHaveAttribute('href', '/applications/1/regions/claim-rules');
    // recommendation card
    expect(screen.getByRole('heading', { name: 'Switch to LFU' })).toBeInTheDocument();
    expect(screen.getByText('69.4%')).toBeInTheDocument();
    expect(screen.getByText('80.9%')).toBeInTheDocument();
    expect(screen.getByText('92')).toBeInTheDocument();
    // application card
    expect(screen.getByRole('link', { name: 'Claims Service' })).toBeInTheDocument();
    expect(screen.getByText(/Environment: Staging/)).toBeInTheDocument();
    expect(screen.getByText(/Last telemetry received:/)).toBeInTheDocument();
    // region table
    const table = screen.getByRole('table', { name: 'All cache regions' });
    expect(within(table).getByRole('link', { name: 'claim-rules' })).toBeInTheDocument();
    expect(within(table).getByRole('link', { name: 'claim-lookup' })).toBeInTheDocument();
    // timeline + donut
    expect(screen.getByText('Hits and misses (all applications)')).toBeInTheDocument();
    expect(screen.getByRole('img', { name: /Hits 18,280/ })).toBeInTheDocument();
  });
});

describe('OverviewPage: loading, empty and error states', () => {
  it('shows skeletons while the first response is pending', () => {
    vi.spyOn(api, 'overview').mockReturnValue(new Promise(() => undefined));
    renderWithProviders(<OverviewPage />);
    expect(screen.getByText('Loading metrics…')).toBeInTheDocument();
    expect(screen.queryByRole('group', { name: 'Projects monitored' })).toBeNull();
    expect(screen.getByText('Connecting…')).toBeInTheDocument();
  });

  it('shows a helpful empty state when nothing has reported yet', async () => {
    vi.spyOn(api, 'overview').mockResolvedValue(emptyOverview());
    renderWithProviders(<OverviewPage />, { role: 'ENGINEER' });
    expect(await screen.findByRole('heading', { name: 'No telemetry yet' })).toBeInTheDocument();
    expect(screen.getAllByText(/start a demo service or run a simulation/i).length).toBeGreaterThan(0);
    expect(screen.getByRole('link', { name: 'Run a simulation' })).toHaveAttribute('href', '/simulations');
    expect(within(tile('Overall hit rate')).getByText('0.00%')).toBeInTheDocument();
    expect(screen.getByText('No cache regions reported yet')).toBeInTheDocument();
  });

  it('hides the simulation shortcut from viewers in the empty state', async () => {
    vi.spyOn(api, 'overview').mockResolvedValue(emptyOverview());
    renderWithProviders(<OverviewPage />, { role: 'VIEWER' });
    await screen.findByRole('heading', { name: 'No telemetry yet' });
    expect(screen.queryByRole('link', { name: 'Run a simulation' })).toBeNull();
  });

  it('shows an error with Retry, and recovers when retried', async () => {
    const overview = vi.spyOn(api, 'overview');
    overview.mockRejectedValueOnce(new Error('Service unavailable'));
    overview.mockResolvedValue(makeOverview());
    renderWithProviders(<OverviewPage />, { role: 'ENGINEER' });
    expect(await screen.findByText('Could not load the overview')).toBeInTheDocument();
    expect(screen.getByText('Service unavailable')).toBeInTheDocument();
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByRole('group', { name: 'Projects monitored' })).toBeInTheDocument();
    expect(screen.queryByText('Could not load the overview')).toBeNull();
  });
});
