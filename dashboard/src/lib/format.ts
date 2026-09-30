/**
 * Number / time formatting helpers used everywhere in the UI so the same value
 * always renders the same way. All helpers are pure and null-safe.
 */

const EM_DASH = '—';

function isFiniteNumber(v: number | null | undefined): v is number {
  return typeof v === 'number' && Number.isFinite(v);
}

/** Percent with 2 decimals by default: `91.4 -> "91.40%"`. `null` renders as an em dash. */
export function formatPercent(value: number | null | undefined, digits = 2): string {
  if (!isFiniteNumber(value)) return EM_DASH;
  return `${value.toFixed(digits)}%`;
}

/** Integer with thousands separators: `1234567 -> "1,234,567"`. */
export function formatInt(value: number | null | undefined): string {
  if (!isFiniteNumber(value)) return EM_DASH;
  return Math.round(value).toLocaleString('en-US');
}

/** Compact integer: `999 -> "999"`, `12_940 -> "12.9K"`, `3_400_000 -> "3.4M"`. */
export function formatCompact(value: number | null | undefined): string {
  if (!isFiniteNumber(value)) return EM_DASH;
  const abs = Math.abs(value);
  const sign = value < 0 ? '-' : '';
  const units: Array<[number, string]> = [
    [1e12, 'T'],
    [1e9, 'B'],
    [1e6, 'M'],
    [1e3, 'K'],
  ];
  for (const [size, suffix] of units) {
    if (abs >= size) {
      const scaled = abs / size;
      const text = scaled >= 100 ? scaled.toFixed(0) : scaled.toFixed(1).replace(/\.0$/, '');
      return `${sign}${text}${suffix}`;
    }
  }
  return `${sign}${Math.round(abs)}`;
}

/**
 * Bytes -> B / KB / MB / GB / TB (binary units, 1 KB = 1024 B).
 * Cache memory is always an *estimate* — callers add the word "estimated" in the label.
 */
export function formatBytes(bytes: number | null | undefined, digits?: number): string {
  if (!isFiniteNumber(bytes)) return EM_DASH;
  const abs = Math.abs(bytes);
  if (abs < 1024) return `${Math.round(bytes)} B`;
  const units = ['KB', 'MB', 'GB', 'TB'];
  let value = abs / 1024;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  const d = digits ?? (value >= 100 ? 0 : 1);
  const sign = bytes < 0 ? '-' : '';
  return `${sign}${value.toFixed(d)} ${units[unit]}`;
}

/** "~132.0 MB (estimated)" style string for places that have no separate label. */
export function formatEstimatedBytes(bytes: number | null | undefined): string {
  if (!isFiniteNumber(bytes)) return EM_DASH;
  return `${formatBytes(bytes)} (est.)`;
}

/** Megabytes (binary) to bytes, rounded to whole bytes. */
export function megabytesToBytes(mb: number): number {
  return Math.round(mb * 1024 * 1024);
}

export function bytesToMegabytes(bytes: number): number {
  return bytes / (1024 * 1024);
}

/**
 * Humanised duration from milliseconds:
 * `450 -> "450 ms"`, `45_000 -> "45s"`, `150_000 -> "2m 30s"`, `3_900_000 -> "1h 5m"`, `90_000_000 -> "1d 1h"`.
 */
export function formatDuration(ms: number | null | undefined): string {
  if (!isFiniteNumber(ms)) return EM_DASH;
  if (ms < 0) return `-${formatDuration(-ms)}`;
  if (ms < 1000) return `${Math.round(ms)} ms`;
  const totalSeconds = Math.floor(ms / 1000);
  const days = Math.floor(totalSeconds / 86400);
  const hours = Math.floor((totalSeconds % 86400) / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  if (days > 0) return hours > 0 ? `${days}d ${hours}h` : `${days}d`;
  if (hours > 0) return minutes > 0 ? `${hours}h ${minutes}m` : `${hours}h`;
  if (minutes > 0) return seconds > 0 ? `${minutes}m ${seconds}s` : `${minutes}m`;
  return `${seconds}s`;
}

/** Latency in ms with adaptive precision: `0.012 -> "0.012 ms"`, `3.456 -> "3.46 ms"`. */
export function formatLatencyMs(ms: number | null | undefined): string {
  if (!isFiniteNumber(ms)) return EM_DASH;
  if (ms === 0) return '0 ms';
  if (ms < 0.1) return `${ms.toFixed(3)} ms`;
  if (ms < 10) return `${ms.toFixed(2)} ms`;
  return `${ms.toFixed(1)} ms`;
}

/**
 * Relative time: `"3 s ago"`, `"2 min ago"`, `"5 h ago"`, `"3 d ago"`; `null` -> "never".
 * `now` is injectable so tests and live indicators stay deterministic.
 */
export function formatRelativeTime(
  value: string | number | Date | null | undefined,
  now: number = Date.now(),
): string {
  if (value === null || value === undefined) return 'never';
  const t = value instanceof Date ? value.getTime() : typeof value === 'number' ? value : Date.parse(value);
  if (!Number.isFinite(t)) return 'unknown';
  return formatSecondsAgo(Math.round((now - t) / 1000));
}

/** Relative time from a number of seconds already computed (e.g. `secondsSinceLastTelemetry`). */
export function formatSecondsAgo(seconds: number | null | undefined): string {
  if (!isFiniteNumber(seconds)) return 'never';
  if (seconds < -1) return 'in the future';
  const s = Math.max(0, seconds);
  if (s < 1) return 'just now';
  if (s < 60) return `${Math.floor(s)} s ago`;
  if (s < 3600) return `${Math.floor(s / 60)} min ago`;
  if (s < 86400) return `${Math.floor(s / 3600)} h ago`;
  return `${Math.floor(s / 86400)} d ago`;
}

function pad2(n: number): string {
  return n < 10 ? `0${n}` : String(n);
}

/** Clock time `HH:MM:SS` in the viewer's local zone (or UTC when `utc` is true). */
export function formatClock(value: string | number | Date | null | undefined, utc = false): string {
  if (value === null || value === undefined) return EM_DASH;
  const d = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(d.getTime())) return EM_DASH;
  const h = utc ? d.getUTCHours() : d.getHours();
  const m = utc ? d.getUTCMinutes() : d.getMinutes();
  const s = utc ? d.getUTCSeconds() : d.getSeconds();
  return `${pad2(h)}:${pad2(m)}:${pad2(s)}`;
}

/** `HH:MM` for chart axes. */
export function formatClockShort(value: string | number | Date, utc = false): string {
  return formatClock(value, utc).slice(0, 5);
}

/** Date + time for tables: `2026-09-30 09:15:30`. */
export function formatDateTime(value: string | number | Date | null | undefined, utc = false): string {
  if (value === null || value === undefined) return EM_DASH;
  const d = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(d.getTime())) return EM_DASH;
  const y = utc ? d.getUTCFullYear() : d.getFullYear();
  const mo = (utc ? d.getUTCMonth() : d.getMonth()) + 1;
  const da = utc ? d.getUTCDate() : d.getDate();
  return `${y}-${pad2(mo)}-${pad2(da)} ${formatClock(d, utc)}`;
}

/** Title-case an ENUM_VALUE: `ENTRY_LIMIT_EVICTED -> "Entry limit evicted"`. */
export function humanizeEnum(value: string): string {
  const lower = value.toLowerCase().replace(/_/g, ' ');
  return lower.charAt(0).toUpperCase() + lower.slice(1);
}

/** Plural helper: `plural(1, "region") -> "1 region"`, `plural(3, "region") -> "3 regions"`. */
export function plural(count: number, singular: string, pluralForm?: string): string {
  return `${formatInt(count)} ${count === 1 ? singular : (pluralForm ?? `${singular}s`)}`;
}

/** Signed percentage points: `11.5 -> "+11.5 pts"`, `-3 -> "-3.0 pts"`. */
export function formatPoints(value: number | null | undefined): string {
  if (!isFiniteNumber(value)) return EM_DASH;
  const sign = value > 0 ? '+' : value < 0 ? '-' : '';
  return `${sign}${Math.abs(value).toFixed(1)} pts`;
}
