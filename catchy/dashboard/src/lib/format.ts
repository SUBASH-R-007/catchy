/** Number formatters for metrics. All are pure and locale-stable (en-US). */

const compact = new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 });
const integer = new Intl.NumberFormat('en-US', { maximumFractionDigits: 0 });

function finite(n: number): number {
  return Number.isFinite(n) ? n : 0;
}

/** 0.8123 → "81.2%". Values are clamped to 0–1. */
export function formatPercent(rate: number, digits = 1): string {
  const clamped = Math.min(1, Math.max(0, finite(rate)));
  return `${(clamped * 100).toFixed(digits)}%`;
}

/** 812344 → "812.3K"; values below 1,000 are shown exactly. */
export function formatCompact(n: number): string {
  const v = finite(n);
  return Math.abs(v) < 1000 ? integer.format(v) : compact.format(v);
}

/** 812344 → "812,344". */
export function formatInteger(n: number): string {
  return integer.format(finite(n));
}

/** Get latency in microseconds: 0.8 → "0.8 µs", 1520 → "1.52 ms". */
export function formatMicros(us: number): string {
  const v = Math.max(0, finite(us));
  if (v < 10) return `${v.toFixed(1)} µs`;
  if (v < 1000) return `${Math.round(v)} µs`;
  return `${(v / 1000).toFixed(2)} ms`;
}

/** A duration in milliseconds, scaled to the largest sensible unit: 9748128 → "2.7 h". */
export function formatDurationMs(ms: number): string {
  const v = Math.max(0, finite(ms));
  if (v < 1000) return `${Math.round(v)} ms`;
  if (v < 60_000) return `${(v / 1000).toFixed(1)} s`;
  if (v < 3_600_000) return `${(v / 60_000).toFixed(1)} min`;
  if (v < 86_400_000) return `${(v / 3_600_000).toFixed(1)} h`;
  return `${(v / 86_400_000).toFixed(1)} d`;
}

/** 40.624 → "$40.62"; the currency label is configurable (SPEC 9.3). */
export function formatMoney(amount: number, currency = '$'): string {
  const v = finite(amount);
  const text = v.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  return `${currency}${text}`;
}

/** Epoch ms → "14:03:21" (24 h, local time). */
export function formatClock(ts: number): string {
  const d = new Date(ts);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

/** 5012 ops/s → "5.0K/s". */
export function formatRate(perSecond: number): string {
  return `${formatCompact(perSecond)}/s`;
}
