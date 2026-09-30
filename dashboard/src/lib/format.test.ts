import { describe, expect, it } from 'vitest';
import {
  formatClock,
  formatCompact,
  formatDurationMs,
  formatInteger,
  formatMicros,
  formatMoney,
  formatPercent,
  formatRate,
} from './format';

describe('formatters', () => {
  it('formats percentages with clamping and NaN safety', () => {
    expect(formatPercent(0.8123)).toBe('81.2%');
    expect(formatPercent(0.8123, 0)).toBe('81%');
    expect(formatPercent(0)).toBe('0.0%');
    expect(formatPercent(1.4)).toBe('100.0%');
    expect(formatPercent(Number.NaN)).toBe('0.0%');
  });

  it('formats counts', () => {
    expect(formatCompact(999)).toBe('999');
    expect(formatCompact(812344)).toBe('812.3K');
    expect(formatCompact(1_250_000)).toBe('1.3M');
    expect(formatInteger(812344)).toBe('812,344');
    expect(formatRate(5012)).toBe('5K/s');
  });

  it('formats get latency in µs, switching to ms', () => {
    expect(formatMicros(0.8)).toBe('0.8 µs');
    expect(formatMicros(42.4)).toBe('42 µs');
    expect(formatMicros(1520)).toBe('1.52 ms');
    expect(formatMicros(-1)).toBe('0.0 µs');
  });

  it('scales durations to the largest sensible unit', () => {
    expect(formatDurationMs(850)).toBe('850 ms');
    expect(formatDurationMs(12_300)).toBe('12.3 s');
    expect(formatDurationMs(270_000)).toBe('4.5 min');
    expect(formatDurationMs(9_748_128)).toBe('2.7 h');
    expect(formatDurationMs(3 * 86_400_000)).toBe('3.0 d');
  });

  it('formats money with a configurable currency label', () => {
    expect(formatMoney(40.624)).toBe('$40.62');
    expect(formatMoney(1234.5, '€')).toBe('€1,234.50');
  });

  it('formats clock time as HH:MM:SS', () => {
    expect(formatClock(new Date(2026, 8, 30, 9, 5, 7).getTime())).toBe('09:05:07');
  });
});
