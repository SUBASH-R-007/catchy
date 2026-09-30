import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { ChipCard } from './ChipCard';
import { EmptyState } from './EmptyState';
import { ErrorState } from './ErrorState';
import { MetricTile } from './MetricTile';
import { PolicyBadge } from './PolicyBadge';
import { SampleBadge } from './SampleBadge';
import { Skeleton } from './Skeleton';

describe('MetricTile', () => {
  it('renders label, value and an info button in a labelled group', async () => {
    const user = userEvent.setup();
    render(
      <MetricTile
        label="Hit rate"
        value="84.2%"
        info="Share of reads served from the cache."
        delta={{ text: '+2.1 pts', direction: 'up', good: true }}
        sublabel="last 10 s"
      />,
    );

    const group = screen.getByRole('group', { name: 'Hit rate: 84.2%' });
    expect(group).toHaveTextContent('Hit rate');
    expect(screen.getByText('84.2%')).toHaveClass('font-mono', 'tabular-nums', 'text-2xl');
    expect(screen.getByText('last 10 s')).toBeInTheDocument();

    const delta = screen.getByText('+2.1 pts');
    expect(delta).toHaveClass('text-good');
    expect(delta).toHaveTextContent('Up: +2.1 pts');

    await user.click(screen.getByRole('button', { name: 'About Hit rate' }));
    expect(screen.getByRole('dialog', { name: 'Hit rate' })).toHaveTextContent(
      'Share of reads served from the cache.',
    );
  });

  it('colours deltas by whether they are good news', () => {
    const { rerender } = render(
      <MetricTile
        label="p99"
        value="120 µs"
        info="i"
        delta={{ text: '+8 µs', direction: 'up', good: false }}
      />,
    );
    expect(screen.getByText('+8 µs')).toHaveClass('text-critical');

    rerender(
      <MetricTile
        label="p99"
        value="120 µs"
        info="i"
        delta={{ text: '0', direction: 'flat', good: true }}
      />,
    );
    expect(screen.getByText('0')).toHaveClass('text-muted');

    rerender(
      <MetricTile label="p99" value="120 µs" info="i" delta={{ text: '-3', direction: 'down' }} />,
    );
    expect(screen.getByText('-3')).toHaveClass('text-muted');
  });
});

describe('ChipCard', () => {
  it('is a section labelled by its title, with the silkscreen label and info popover', () => {
    render(
      <ChipCard label="U1 · Hit rate" title="Hit rate over time" info="Rolling 10 s hit rate.">
        <p>chart</p>
      </ChipCard>,
    );
    const section = screen.getByRole('region', { name: 'Hit rate over time' });
    expect(section.tagName).toBe('SECTION');
    expect(section).toHaveClass('chip-card');
    expect(
      screen.getByRole('heading', { level: 2, name: 'Hit rate over time' }),
    ).toBeInTheDocument();
    expect(screen.getByText('U1 · Hit rate')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'About Hit rate over time' })).toBeInTheDocument();
    expect(screen.getByText('chart')).toBeInTheDocument();
  });

  it('falls back to the silkscreen label for its name and renders actions', () => {
    render(
      <ChipCard
        label="U4 · Event log"
        info="The last 50 removals."
        actions={<button type="button">Pause</button>}
      />,
    );
    expect(screen.getByRole('region', { name: 'U4 · Event log' })).toBeInTheDocument();
    expect(screen.queryByRole('heading')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Pause' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'About Event log' })).toBeInTheDocument();
  });
});

describe('PolicyBadge', () => {
  it.each([
    ['LRU', 'LRU', 'solid'],
    ['LFU', 'LFU', 'dashed'],
    ['LFU_DECAY', 'LFU decay', 'dotted'],
    ['OPTIMAL', 'Optimal', 'long dash'],
  ] as const)('shows %s with its line style', (policy, label, lineStyle) => {
    const { container } = render(<PolicyBadge policy={policy} />);
    expect(screen.getByText(label)).toBeInTheDocument();
    expect(screen.getByText(`(${lineStyle} line)`)).toHaveClass('sr-only');
    expect(container.firstElementChild).toHaveTextContent(`${label} (${lineStyle} line)`);
    expect(container.querySelector('svg')).toHaveAttribute('aria-hidden', 'true');
  });
});

describe('feedback states', () => {
  it('EmptyState shows title, message and action', () => {
    render(
      <EmptyState
        title="No caches yet"
        message="Create one in the Playground."
        action={<button type="button">Create cache</button>}
      />,
    );
    expect(screen.getByText('No caches yet')).toBeInTheDocument();
    expect(screen.getByText('Create one in the Playground.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Create cache' })).toBeInTheDocument();
  });

  it('Skeleton is a busy status labelled "Loading" by default', () => {
    const { rerender } = render(<Skeleton lines={4} />);
    const status = screen.getByRole('status', { name: 'Loading' });
    expect(status).toHaveAttribute('aria-busy', 'true');
    expect(status.querySelectorAll('.skeleton-block')).toHaveLength(4);

    rerender(<Skeleton label="Loading chart" lines={1} />);
    expect(screen.getByRole('status', { name: 'Loading chart' })).toBeInTheDocument();
  });

  it('ErrorState is an alert with an optional retry button', async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    render(<ErrorState message="The server did not respond." onRetry={onRetry} />);
    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent('Something went wrong');
    expect(alert).toHaveTextContent('The server did not respond.');
    await user.click(screen.getByRole('button', { name: 'Retry' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('SampleBadge shows its text', () => {
    const { rerender } = render(<SampleBadge />);
    expect(screen.getByText('Sample data')).toBeInTheDocument();
    rerender(<SampleBadge text="Estimate" />);
    expect(screen.getByText('Estimate')).toBeInTheDocument();
  });
});
