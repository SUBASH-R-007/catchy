import { fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { completeBuckets } from '../components/TimelinePanel';
import { ChartFrame } from './ChartFrame';
import { Donut } from './Donut';
import { HBars } from './HBars';
import { Meter, MemoryMeter } from './Meter';
import { niceMax, niceTicks, formatAxisValue, spreadIndexes } from './scale';
import { Sparkline } from './Sparkline';
import { StackedBars } from './StackedBars';
import { TimelineChart } from './TimelineChart';

const hitMiss = [
  { key: 'hits', label: 'Hits', value: 9140, color: 'var(--series-hit)', valueLabel: '9,140' },
  { key: 'misses', label: 'Misses', value: 860, color: 'var(--series-miss)', valueLabel: '860' },
];

describe('Donut', () => {
  it('renders an accessible summary, one arc per non-empty segment and a value legend', () => {
    const { container } = render(<Donut segments={hitMiss} centerLabel="91.4%" centerSub="hit rate" />);
    const svg = screen.getByRole('img');
    expect(svg).toHaveAccessibleName('Hits 9,140 (91.4%), Misses 860 (8.6%)');
    const arcs = Array.from(container.querySelectorAll('circle')).filter((c) => c.getAttribute('stroke')?.startsWith('var(--series'));
    expect(arcs).toHaveLength(2);
    expect(screen.getByText('91.4%')).toBeInTheDocument();
    expect(screen.getByText(/9,140 · 91\.4%/)).toBeInTheDocument();
  });

  it('shows an empty state instead of arcs when there is no data', () => {
    const { container } = render(
      <Donut segments={hitMiss.map((s) => ({ ...s, value: 0 }))} centerLabel="0%" centerSub="hit rate" emptyLabel="No requests yet" />,
    );
    expect(screen.getByRole('img')).toHaveAccessibleName('No requests yet');
    const arcs = Array.from(container.querySelectorAll('circle')).filter((c) => c.getAttribute('stroke')?.startsWith('var(--series'));
    expect(arcs).toHaveLength(0);
    expect(screen.getAllByText('No requests yet').length).toBeGreaterThan(0);
  });
});

describe('Meter thresholds', () => {
  it.each([
    [51.56, 'ok'],
    [80, 'warning'],
    [90, 'serious'],
    [98, 'critical'],
  ] as const)('%s%% uses the %s fill', (percent, severity) => {
    const { container } = render(<Meter percent={percent} label="Memory" />);
    expect(container.querySelector(`.meter__fill--${severity}`)).not.toBeNull();
    const meter = screen.getByRole('meter', { name: 'Memory' });
    expect(meter).toHaveAttribute('aria-valuenow', String(percent));
  });

  it('spells severity out in text, not just color', () => {
    render(<Meter percent={93} label="Memory" showSeverity />);
    expect(screen.getByText(/High \(90%\+\)/)).toBeInTheDocument();
  });

  it('clamps out-of-range values and shows used / max bytes', () => {
    render(<MemoryMeter usedBytes={138_412_032} maxBytes={268_435_456} percent={130} />);
    const meter = screen.getByRole('meter');
    expect(meter).toHaveAttribute('aria-valuenow', '100');
    expect(screen.getByText('132 MB / 256 MB')).toBeInTheDocument();
  });
});

describe('TimelineChart', () => {
  const labels = Array.from({ length: 6 }, (_, i) => new Date(Date.UTC(2026, 8, 30, 9, 0, i * 10)).toISOString());
  const series = [
    { key: 'hits', label: 'Hits', color: 'var(--series-hit)', values: [100, 120, 90, 150, 130, 140] },
    { key: 'misses', label: 'Misses', color: 'var(--series-miss)', values: [10, 12, 9, 15, 13, 14] },
  ];

  it('draws one line per series and labelled axes', () => {
    const { container } = render(<TimelineChart labels={labels} series={series} ariaLabel="Hits and misses" />);
    const paths = Array.from(container.querySelectorAll('path')).filter((p) => p.getAttribute('fill') === 'none');
    expect(paths).toHaveLength(2);
    expect(paths.map((p) => p.getAttribute('stroke'))).toEqual(['var(--series-hit)', 'var(--series-miss)']);
    expect(container.querySelectorAll('text.axis-text').length).toBeGreaterThan(4);
  });

  it('shows a crosshair tooltip with every series when navigating with the keyboard', async () => {
    const user = userEvent.setup();
    render(<TimelineChart labels={labels} series={series} ariaLabel="Hits and misses" />);
    const chart = screen.getByRole('group', { name: /Hits and misses/ });
    chart.focus();
    await user.keyboard('{ArrowRight}');
    const tip = screen.getByRole('status');
    expect(within(tip).getByText('Hits')).toBeInTheDocument();
    expect(within(tip).getByText('Misses')).toBeInTheDocument();
    expect(within(tip).getByText('100')).toBeInTheDocument();
    await user.keyboard('{End}');
    expect(within(screen.getByRole('status')).getByText('140')).toBeInTheDocument();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('status')).toBeNull();
  });

  it('renders an empty message when there are no points', () => {
    render(<TimelineChart labels={[]} series={[]} ariaLabel="Empty" emptyText="No data in this window yet" />);
    expect(screen.getByText('No data in this window yet')).toBeInTheDocument();
  });

  it('says so when every value is zero', () => {
    const zero = [{ key: 'h', label: 'Hits', color: 'var(--series-hit)', values: [0, 0, 0] }];
    render(<TimelineChart labels={labels.slice(0, 3)} series={zero} ariaLabel="Zeros" />);
    expect(screen.getByText('No activity in this window')).toBeInTheDocument();
  });

  it('reveals the same numbers in the table view', async () => {
    const user = userEvent.setup();
    render(
      <ChartFrame title="Traffic" table={{ headers: ['Time', 'Hits'], rows: [['09:00:00', '100'], ['09:00:10', '120']] }}>
        <TimelineChart labels={labels} series={series} ariaLabel="Traffic" />
      </ChartFrame>,
    );
    await user.click(screen.getByRole('button', { name: /table view/i }));
    const table = screen.getByRole('table', { name: /traffic data/i });
    expect(within(table).getByText('120')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /chart view/i }));
    expect(screen.getByRole('group', { name: /Traffic/ })).toBeInTheDocument();
  });
});

describe('completeBuckets', () => {
  const pts = [0, 10, 20, 30].map((s) => ({ bucketStart: new Date(Date.UTC(2026, 8, 30, 9, 0, s)).toISOString() }));
  it('hides the newest bucket while it is still filling', () => {
    expect(completeBuckets(pts, 10, Date.UTC(2026, 8, 30, 9, 0, 35))).toHaveLength(3);
  });
  it('keeps every bucket once the last one has completed or for historical data', () => {
    expect(completeBuckets(pts, 10, Date.UTC(2026, 8, 30, 9, 1, 0))).toHaveLength(4);
  });
  it('never empties a tiny series', () => {
    expect(completeBuckets(pts.slice(0, 2), 10, Date.UTC(2026, 8, 30, 9, 0, 15))).toHaveLength(2);
  });
});

describe('HBars', () => {
  it('draws one bar per row with a value at the tip', () => {
    render(
      <HBars
        percent
        ariaLabel="Policies"
        groups={[
          {
            rows: [
              { key: 'lru', label: 'LRU', value: 69.4, color: 'var(--series-lru)', valueLabel: '69.4%' },
              { key: 'lfu', label: 'LFU', value: 80.9, color: 'var(--series-lfu)', valueLabel: '80.9%' },
            ],
          },
        ]}
      />,
    );
    expect(screen.getByRole('group', { name: 'Policies' })).toBeInTheDocument();
    expect(screen.getByText('69.4%')).toBeInTheDocument();
    expect(screen.getByText('80.9%')).toBeInTheDocument();
  });
  it('shows the empty text with no rows', () => {
    render(<HBars groups={[]} ariaLabel="Empty" emptyText="Nothing yet" />);
    expect(screen.getByText('Nothing yet')).toBeInTheDocument();
  });
});

describe('StackedBars and Sparkline', () => {
  it('renders one rect per positive segment and skips zero segments', () => {
    const { container } = render(
      <StackedBars
        ariaLabel="Mix"
        rows={[
          {
            key: 'r',
            label: 'claim-rules',
            totalLabel: '91.4%',
            segments: [
              { key: 'h', label: 'Hits', value: 9140, color: 'var(--series-hit)' },
              { key: 'm', label: 'Misses', value: 860, color: 'var(--series-miss)' },
              { key: 'z', label: 'Zero', value: 0, color: 'var(--series-evict)' },
            ],
          },
        ]}
      />,
    );
    const rects = Array.from(container.querySelectorAll('rect')).filter((r) => r.getAttribute('stroke') === 'var(--surface)');
    expect(rects).toHaveLength(2);
    expect(screen.getByText('91.4%')).toBeInTheDocument();
  });
  it('shows empty text for no rows', () => {
    render(<StackedBars ariaLabel="Mix" rows={[]} emptyText="No regions" />);
    expect(screen.getByText('No regions')).toBeInTheDocument();
  });
  it('draws a sparkline with data and degrades with fewer than two points', () => {
    const { container, rerender } = render(<Sparkline values={[10, 20, 15, 30]} ariaLabel="Trend" />);
    expect(container.querySelectorAll('path').length).toBe(2);
    rerender(<Sparkline values={[10]} ariaLabel="Trend" />);
    expect(screen.getByRole('img')).toHaveAccessibleName('Trend (not enough data)');
  });
});

describe('scale helpers', () => {
  it('rounds axis maxima to nice values', () => {
    expect(niceMax(0)).toBe(1);
    expect(niceMax(87)).toBe(100);
    expect(niceMax(1234)).toBe(2000);
    expect(niceMax(2300)).toBe(2500);
    expect(niceTicks(1000, 4)).toEqual([0, 250, 500, 750, 1000]);
    expect(formatAxisValue(1500)).toBe('1.5K');
    expect(formatAxisValue(2_000_000)).toBe('2M');
    expect(formatAxisValue(250)).toBe('250');
  });
  it('spreads tick indexes including both ends', () => {
    expect(spreadIndexes(90, 4)).toEqual([0, 30, 59, 89]);
    expect(spreadIndexes(3, 6)).toEqual([0, 1, 2]);
    expect(spreadIndexes(0, 4)).toEqual([]);
  });
});

// keep fireEvent imported for pointer interaction coverage
describe('TimelineChart pointer interaction', () => {
  it('shows the tooltip on pointer move and hides it on leave', () => {
    const labels = Array.from({ length: 4 }, (_, i) => new Date(Date.UTC(2026, 8, 30, 9, 0, i * 10)).toISOString());
    render(<TimelineChart labels={labels} series={[{ key: 'h', label: 'Hits', color: 'var(--series-hit)', values: [1, 2, 3, 4] }]} ariaLabel="Pointer" />);
    const chart = screen.getByRole('group', { name: /Pointer/ });
    fireEvent.pointerMove(chart, { clientX: 100, clientY: 50 });
    expect(screen.getByRole('status')).toBeInTheDocument();
    fireEvent.pointerLeave(chart);
    expect(screen.queryByRole('status')).toBeNull();
  });
});
