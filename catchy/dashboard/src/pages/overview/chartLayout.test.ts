import { describe, expect, it } from 'vitest';
import { endLabelTops, markerLabels } from './chartLayout';

describe('endLabelTops', () => {
  it('places labels at their values when far apart', () => {
    const tops = endLabelTops(
      [
        { key: 'a', value: 0.8 },
        { key: 'b', value: 0.2 },
      ],
      0,
      100,
      18,
    );
    expect(tops.get('a')).toBeCloseTo(20);
    expect(tops.get('b')).toBeCloseTo(80);
  });

  it('pushes overlapping labels apart by the minimum gap', () => {
    const tops = endLabelTops(
      [
        { key: 'lru', value: 0.77 },
        { key: 'lfu', value: 0.78 },
      ],
      0,
      200,
      18,
    );
    const lfu = tops.get('lfu') ?? 0;
    const lru = tops.get('lru') ?? 0;
    expect(lru - lfu).toBeGreaterThanOrEqual(18);
  });

  it('keeps the stack inside the plot', () => {
    const tops = endLabelTops(
      [
        { key: 'a', value: 0 },
        { key: 'b', value: 0 },
      ],
      10,
      100,
      18,
    );
    expect(Math.max(...tops.values())).toBeLessThanOrEqual(110);
  });
});

describe('markerLabels', () => {
  const caption = 'Fake stream (dev only): a scan floods lru-A';

  it('keeps the full caption when there is room', () => {
    const [m] = markerLabels([{ ts: 0, caption }], [0, 60_000], 1000);
    expect(m?.text).toBe(caption);
    expect(m?.n).toBe(1);
    expect(m?.row).toBe(0);
  });

  it('alternates rows and truncates to the room before the next same-row marker', () => {
    const labels = markerLabels(
      [
        { ts: 0, caption },
        { ts: 5_000, caption },
        { ts: 10_000, caption },
      ],
      [0, 60_000],
      600,
    );
    expect(labels.map((l) => l.row)).toEqual([0, 1, 0]);
    // Marker 1 has 100 px before marker 3 on the same row.
    expect(labels[0]?.text.endsWith('…')).toBe(true);
    expect((labels[0]?.text.length ?? 0) * 7.2).toBeLessThanOrEqual(100);
    // The last marker has the rest of the plot.
    expect(labels[2]?.text).toBe(caption);
  });

  it('drops the caption when there is no room at all', () => {
    const labels = markerLabels(
      [
        { ts: 0, caption },
        { ts: 100, caption },
        { ts: 200, caption },
      ],
      [0, 60_000],
      600,
    );
    expect(labels[0]?.text).toBe('');
  });
});
