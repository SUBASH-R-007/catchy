import { describe, expect, it } from 'vitest';
import {
  bytesToMegabytes,
  formatBytes,
  formatClock,
  formatCompact,
  formatDateTime,
  formatDuration,
  formatEstimatedBytes,
  formatInt,
  formatLatencyMs,
  formatPercent,
  formatPoints,
  formatRelativeTime,
  formatSecondsAgo,
  humanizeEnum,
  megabytesToBytes,
  plural,
} from './format';

describe('formatPercent', () => {
  it('uses two decimals by default', () => {
    expect(formatPercent(91.4)).toBe('91.40%');
    expect(formatPercent(0)).toBe('0.00%');
    expect(formatPercent(8.605)).toMatch(/^8\.6[01]%$/);
  });
  it('honours the digits argument and renders null-ish as an em dash', () => {
    expect(formatPercent(91.456, 1)).toBe('91.5%');
    expect(formatPercent(null)).toBe('—');
    expect(formatPercent(undefined)).toBe('—');
    expect(formatPercent(Number.NaN)).toBe('—');
  });
});

describe('formatInt / formatCompact', () => {
  it('groups thousands', () => {
    expect(formatInt(1234567)).toBe('1,234,567');
    expect(formatInt(0)).toBe('0');
    expect(formatInt(null)).toBe('—');
  });
  it('compacts large numbers', () => {
    expect(formatCompact(999)).toBe('999');
    expect(formatCompact(12_940)).toBe('12.9K');
    expect(formatCompact(1_000)).toBe('1K');
    expect(formatCompact(1_428_296)).toBe('1.4M');
    expect(formatCompact(3_400_000_000)).toBe('3.4B');
    expect(formatCompact(250_000)).toBe('250K');
    expect(formatCompact(-2500)).toBe('-2.5K');
    expect(formatCompact(undefined)).toBe('—');
  });
});

describe('formatBytes', () => {
  it('scales through B / KB / MB / GB with binary units', () => {
    expect(formatBytes(512)).toBe('512 B');
    expect(formatBytes(1800)).toBe('1.8 KB');
    expect(formatBytes(1024)).toBe('1.0 KB');
    expect(formatBytes(138_412_032)).toBe('132 MB');
    expect(formatBytes(268_435_456)).toBe('256 MB');
    expect(formatBytes(5 * 1024 ** 3)).toBe('5.0 GB');
  });
  it('keeps one decimal below 100 and none above', () => {
    expect(formatBytes(1.5 * 1024 * 1024)).toBe('1.5 MB');
    expect(formatBytes(150 * 1024 * 1024)).toBe('150 MB');
  });
  it('handles null and marks estimates', () => {
    expect(formatBytes(null)).toBe('—');
    expect(formatEstimatedBytes(1800)).toBe('1.8 KB (est.)');
  });
  it('converts megabytes both ways', () => {
    expect(megabytesToBytes(256)).toBe(268_435_456);
    expect(megabytesToBytes(0.5)).toBe(524_288);
    expect(bytesToMegabytes(268_435_456)).toBe(256);
  });
});

describe('formatDuration', () => {
  it('humanises milliseconds up to days', () => {
    expect(formatDuration(450)).toBe('450 ms');
    expect(formatDuration(45_000)).toBe('45s');
    expect(formatDuration(150_000)).toBe('2m 30s');
    expect(formatDuration(900_000)).toBe('15m');
    expect(formatDuration(3_900_000)).toBe('1h 5m');
    expect(formatDuration(7_200_000)).toBe('2h');
    expect(formatDuration(90_000_000)).toBe('1d 1h');
    expect(formatDuration(null)).toBe('—');
  });
  it('formats latency with adaptive precision', () => {
    expect(formatLatencyMs(0)).toBe('0 ms');
    expect(formatLatencyMs(0.012)).toBe('0.012 ms');
    expect(formatLatencyMs(3.456)).toBe('3.46 ms');
    expect(formatLatencyMs(42.26)).toBe('42.3 ms');
  });
});

describe('relative time', () => {
  const now = Date.parse('2026-09-30T09:15:30Z');
  it('renders seconds, minutes, hours and days ago', () => {
    expect(formatRelativeTime('2026-09-30T09:15:27Z', now)).toBe('3 s ago');
    expect(formatRelativeTime('2026-09-30T09:13:30Z', now)).toBe('2 min ago');
    expect(formatRelativeTime('2026-09-30T04:15:30Z', now)).toBe('5 h ago');
    expect(formatRelativeTime('2026-09-27T09:15:30Z', now)).toBe('3 d ago');
  });
  it('handles missing, invalid and just-now values', () => {
    expect(formatRelativeTime(null, now)).toBe('never');
    expect(formatRelativeTime('not a date', now)).toBe('unknown');
    expect(formatRelativeTime(now, now)).toBe('just now');
    expect(formatSecondsAgo(3)).toBe('3 s ago');
    expect(formatSecondsAgo(null)).toBe('never');
  });
});

describe('clock and misc helpers', () => {
  it('formats clock times and date-times (UTC for determinism)', () => {
    expect(formatClock('2026-09-30T13:18:08Z', true)).toBe('13:18:08');
    expect(formatDateTime('2026-09-30T09:05:03Z', true)).toBe('2026-09-30 09:05:03');
    expect(formatClock(null)).toBe('—');
  });
  it('humanises enums, plurals and signed points', () => {
    expect(humanizeEnum('ENTRY_LIMIT_EVICTED')).toBe('Entry limit evicted');
    expect(plural(1, 'region')).toBe('1 region');
    expect(plural(3, 'region')).toBe('3 regions');
    expect(plural(2, 'policy', 'policies')).toBe('2 policies');
    expect(formatPoints(11.5)).toBe('+11.5 pts');
    expect(formatPoints(-3)).toBe('-3.0 pts');
    expect(formatPoints(0)).toBe('0.0 pts');
  });
});
